#!/usr/bin/env bash
# 운영 EC2·RDS 를 쓰지 않을 때 멈추고, 필요할 때 다시 켠다. 절차·비용은 docs/ops/운영-가이드.md "쓸 때만 켜기".
#
#   bash infra/scripts/power.sh status   # 지금 상태와 판정(켜짐·꺼짐·주의). 아무것도 바꾸지 않는다
#   bash infra/scripts/power.sh down     # 경보 알림 멈춤 → EC2 정지 → RDS 정지
#   bash infra/scripts/power.sh up       # RDS 시작·대기 → EC2 시작·대기 → 8080 응답 확인 → 경보 알림 켬
#
# 자원은 Terraform state 가 아니라 이름으로 찾는다(EC2 태그 Name=caffeine-app, RDS caffeine-db, 알람 caffeine-*).
# 그래서 state 가 없는 체크아웃에서도 돈다. PC 의 AWS 자격 증명을 쓴다.
set -euo pipefail

REGION="${AWS_REGION:-ap-northeast-2}"
EC2_NAME="caffeine-app"
DB_ID="caffeine-db"
ALARM_PREFIX="caffeine-"
HEALTH_TIMEOUT_SEC=300

aws_() { aws --region "$REGION" --output text "$@"; }

ec2_id() {
  aws_ ec2 describe-instances \
    --filters "Name=tag:Name,Values=$EC2_NAME" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query 'Reservations[].Instances[].InstanceId'
}
ec2_state() { aws_ ec2 describe-instances --instance-ids "$1" --query 'Reservations[0].Instances[0].State.Name'; }
ec2_ip()    { aws_ ec2 describe-instances --instance-ids "$1" --query 'Reservations[0].Instances[0].PublicIpAddress'; }
db_state()  { aws_ rds describe-db-instances --db-instance-identifier "$DB_ID" --query 'DBInstances[0].DBInstanceStatus'; }
alarms()    { aws_ cloudwatch describe-alarms --alarm-name-prefix "$ALARM_PREFIX" --query 'MetricAlarms[].AlarmName'; }
# 멈춘 RDS 를 AWS 가 다시 켜는 시각(UTC). 멈춰 있지 않으면 None
db_auto_restart() { aws_ rds describe-db-instances --db-instance-identifier "$DB_ID" --query 'DBInstances[0].AutomaticRestartTime'; }

require_ec2() {
  local id
  id="$(ec2_id)"
  if [ -z "$id" ] || [ "$id" = "None" ]; then
    echo "EC2 '$EC2_NAME' 를 찾지 못했다 (리전 $REGION)" >&2; exit 1
  fi
  case "$id" in *[[:space:]]*) echo "EC2 '$EC2_NAME' 가 여러 대다: $id" >&2; exit 1 ;; esac
  echo "$id"
}

set_alarm_actions() { # enable | disable
  local names
  names="$(alarms)"
  if [ -z "$names" ] || [ "$names" = "None" ]; then
    echo "- 경보 알람 없음 (alerting_enabled=false) — 건너뜀"; return
  fi
  # shellcheck disable=SC2086 # 알람 이름에는 공백이 없다
  aws_ cloudwatch "$1-alarm-actions" --alarm-names $names
  echo "- 경보 알림 $([ "$1" = enable ] && echo 켬 || echo 멈춤): $(echo $names | wc -w | tr -d ' ')개"
}

# EC2·RDS·알림이 서로 맞는지 본다. down 뒤 7일이 지나 RDS 만 다시 켜진 경우와,
# terraform apply 가 멈춰 둔 알림을 다시 켠 경우(Terraform 은 actions_enabled 를 true 로 되돌린다)를 여기서 잡는다
verdict() { # EC2상태 RDS상태 알람수 알림켜진수
  local ec2="$1" db="$2" total="$3" on="$4" ok=1
  case "$ec2" in
    running)
      if [ "$db" != "available" ]; then
        echo "주의: EC2 는 켜져 있는데 RDS 가 '$db' 다. 앱이 DB 에 붙지 못한다 — up 을 실행한다"; ok=0
      fi
      if [ "$on" -lt "$total" ]; then
        echo "주의: 켜져 있는데 알림이 멈춘 알람이 $((total - on))개다. 장애가 나도 알림이 오지 않는다 — up 을 실행한다(up 이 도는 중이면 끝날 때까지 기다린다)"; ok=0
      fi
      if [ "$ok" = 1 ]; then echo "판정: 켜짐 — EC2·RDS 가동, 경보 알림 $([ "$total" -gt 0 ] && echo 켜짐 || echo "없음(알람 0개)")"; fi ;;
    stopped)
      case "$db" in
        stopped|stopping) ;;
        available)
          echo "주의: EC2 는 멈췄는데 RDS 가 켜져 있다(요금이 나간다). 멈춘 지 7일이 지나 AWS 가 다시 켠 것일 수 있다 — 계속 꺼 둘 거면 down 을 다시 실행한다"; ok=0 ;;
        *)
          echo "주의: EC2 는 멈췄는데 RDS 가 '$db' 다. up 이 도는 중이 아니면 잠시 뒤 다시 보고, available 이 되면 down 을 다시 실행한다"; ok=0 ;;
      esac
      if [ "$on" -gt 0 ]; then
        echo "주의: 꺼 둔 상태인데 알림이 켜진 알람이 ${on}개다(terraform apply 를 하면 다시 켜진다) — down 을 다시 실행한다"; ok=0
      fi
      if [ "$ok" = 1 ]; then echo "판정: 꺼짐 — EC2·RDS 정지, 경보 알림 멈춤"; fi ;;
    *) echo "판정: 전환 중 (EC2 '$ec2', RDS '$db') — 잠시 뒤 다시 본다" ;;
  esac
}

cmd_status() {
  local id ec2 db restart rows total=0 on=0
  id="$(require_ec2)"
  ec2="$(ec2_state "$id")"
  db="$(db_state)"
  echo "EC2 $id: $ec2"
  echo "RDS $DB_ID: $db"
  restart="$(db_auto_restart)"
  if [ -n "$restart" ] && [ "$restart" != "None" ]; then
    echo "  AWS 가 RDS 를 다시 켜는 시각: $restart (UTC) — 그 뒤에도 안 쓰면 down 을 다시 실행한다"
  fi
  rows="$(aws_ cloudwatch describe-alarms --alarm-name-prefix "$ALARM_PREFIX" \
    --query 'MetricAlarms[].[AlarmName,ActionsEnabled,StateValue]')"
  if [ -n "$rows" ] && [ "$rows" != "None" ]; then
    echo "경보 알람 (이름 / 알림 켜짐 / 상태):"
    printf '%s\n' "$rows" | sed 's/^/  /'
    total="$(printf '%s\n' "$rows" | awk 'NF { n++ } END { print n + 0 }')"
    on="$(printf '%s\n' "$rows" | awk '$2 == "True" { n++ } END { print n + 0 }')"
  else
    echo "경보 알람 없음 (alerting_enabled=false)"
  fi
  verdict "$ec2" "$db" "$total" "$on"
}

cmd_down() {
  local id st
  id="$(require_ec2)"
  # 먼저 알림을 멈춘다. 그러지 않으면 EC2 가 멈춘 뒤 하트비트 알람이 15분쯤 뒤 울린다
  set_alarm_actions disable

  st="$(ec2_state "$id")"
  if [ "$st" = "stopped" ] || [ "$st" = "stopping" ]; then
    echo "- EC2 이미 $st"
  else
    aws_ ec2 stop-instances --instance-ids "$id" >/dev/null
    echo "- EC2 정지 요청, 멈출 때까지 기다린다"
    aws_ ec2 wait instance-stopped --instance-ids "$id"
    echo "- EC2 stopped"
  fi

  st="$(db_state)"
  case "$st" in
    stopped|stopping) echo "- RDS 이미 $st" ;;
    available)
      aws_ rds stop-db-instance --db-instance-identifier "$DB_ID" >/dev/null
      echo "- RDS 정지 요청 (멈추기까지 몇 분 걸린다. 끝까지 기다리지 않는다)" ;;
    *) echo "- RDS 상태가 '$st' 라 지금은 멈출 수 없다. 잠시 뒤 down 을 다시 실행한다" >&2; exit 1 ;;
  esac

  echo "완료. RDS 는 멈춘 지 7일이 지나면 AWS 가 자동으로 다시 켠다 — 그 뒤에도 안 쓰면 down 을 다시 실행한다."
  echo "몇 분 뒤 status 로 '판정: 꺼짐' 과 다시 켜지는 시각을 확인한다."
}

cmd_up() {
  local id st ip code deadline
  id="$(require_ec2)"

  # RDS 를 먼저 켠다. EC2 가 먼저 뜨면 앱이 DB 에 붙지 못해 재시작을 되풀이한다
  st="$(db_state)"
  case "$st" in
    available) echo "- RDS 이미 available" ;;
    stopped)
      aws_ rds start-db-instance --db-instance-identifier "$DB_ID" >/dev/null
      echo "- RDS 시작 요청, available 까지 기다린다 (보통 5~10분)" ;;
    *) echo "- RDS 상태 '$st', available 까지 기다린다" ;;
  esac
  aws_ rds wait db-instance-available --db-instance-identifier "$DB_ID"
  echo "- RDS available"

  st="$(ec2_state "$id")"
  if [ "$st" = "stopping" ]; then
    aws_ ec2 wait instance-stopped --instance-ids "$id"; st="stopped"
  fi
  if [ "$st" = "stopped" ]; then
    aws_ ec2 start-instances --instance-ids "$id" >/dev/null
    echo "- EC2 시작 요청"
  fi
  aws_ ec2 wait instance-running --instance-ids "$id"
  echo "- EC2 running"

  # 앱은 부팅 때 Docker(enable)와 restart: unless-stopped 로 저절로 뜬다. 8080 이 HTTP 로 답하면 떴다고 본다(401 도 응답이다)
  ip="$(ec2_ip "$id")"
  echo "- 앱 응답 기다림: http://$ip:8080 (최대 ${HEALTH_TIMEOUT_SEC}초)"
  deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SEC ))
  while :; do
    code="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "http://$ip:8080/" || true)"
    if [ -n "$code" ] && [ "$code" != "000" ]; then echo "- 앱 응답 HTTP $code"; break; fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "앱이 ${HEALTH_TIMEOUT_SEC}초 안에 응답하지 않았다. 경보 알림은 멈춘 채로 둔다." >&2
      echo "SSH 로 들어가 'docker ps' 와 'docker logs caffeine-app' 를 본다 (운영 가이드)." >&2
      exit 1
    fi
    sleep 10
  done

  set_alarm_actions enable
  echo "완료. 꺼 둔 동안 '경보' 상태였던 하트비트 알람이 정상으로 돌아오며 '해제' 메일이 한 통 올 수 있다."
  echo "15분쯤 뒤 status 로 caffeine-heartbeat-missing 이 OK 인지 확인한다 — 알림은 상태가 바뀔 때만 가므로 ALARM 인 채로 남으면 아무것도 오지 않는다."
}

case "${1:-}" in
  status) cmd_status ;;
  down)   cmd_down ;;
  up)     cmd_up ;;
  *) echo "사용법: bash $0 {status|down|up}" >&2; exit 2 ;;
esac
