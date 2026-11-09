#!/bin/sh
# 운영 Alertmanager 시작 스크립트 (docker-compose.prod.yml 의 alertmanager 서비스가 실행한다).
#
# alertmanager.yml 의 자리표시자를 .env 값으로 바꾼 사본을 만들고 Alertmanager 를 띄운다.
# Alertmanager 설정 파일은 환경변수를 펼치지 않고, 토픽 ARN 에는 계정 ID 가 들어 있어 레포에 직접 쓰지 않기 때문이다.
#
# 값이 비거나 형식이 틀리면 Alertmanager 만 시작하지 않는다. 앱·Prometheus·Grafana 는 영향이 없다
# (compose 의 필수 변수 :? 를 쓰면 GRAFANA_ADMIN_PASSWORD 때처럼 앱 기동까지 막힌다 — 운영 가이드 "알려진 함정").
# 이미지는 busybox 기반이라 sh·sed·ln 만 쓴다.
set -eu

fail() {
    echo "alertmanager entrypoint: $1 — Alertmanager 를 시작하지 않는다 (docs/alerting-plan.md §4.2)" >&2
    exit 1
}

# 형식 검사. sed 치환에 들어가므로 예상한 문자만 허용한다
case "${ALERT_TOPIC_ARN:-}" in
    arn:aws:sns:*:*:*) ;;
    *) fail ".env 의 ALERT_TOPIC_ARN 이 비었거나 SNS 토픽 ARN 형식이 아니다" ;;
esac
case "${HEARTBEAT_TOPIC_ARN:-}" in
    arn:aws:sns:*:*:*) ;;
    *) fail ".env 의 HEARTBEAT_TOPIC_ARN 이 비었거나 SNS 토픽 ARN 형식이 아니다" ;;
esac
case "${AWS_REGION:-}" in
    "" | *[!a-z0-9-]*) fail ".env 의 AWS_REGION 이 비었거나 형식이 틀렸다 (예: ap-northeast-2)" ;;
esac
for v in "$ALERT_TOPIC_ARN" "$HEARTBEAT_TOPIC_ARN"; do
    case "$v" in
        *[!A-Za-z0-9:_-]*) fail "토픽 ARN 에 허용하지 않는 문자가 있다: $v" ;;
    esac
done

# 사본은 데이터 볼륨(/alertmanager, 컨테이너 사용자 nobody 가 쓸 수 있는 곳)에 둔다. /etc/alertmanager 는 읽기 전용 마운트다
RENDERED=/alertmanager/rendered
mkdir -p "$RENDERED"
sed -e "s|__ALERT_TOPIC_ARN__|${ALERT_TOPIC_ARN}|g" \
    -e "s|__HEARTBEAT_TOPIC_ARN__|${HEARTBEAT_TOPIC_ARN}|g" \
    -e "s|__AWS_REGION__|${AWS_REGION}|g" \
    /etc/alertmanager/alertmanager.yml > "$RENDERED/alertmanager.yml"
# 설정의 templates/*.tmpl 은 설정 파일 기준 상대 경로라 사본 옆에 원본 템플릿 폴더를 잇는다
ln -sfn /etc/alertmanager/templates "$RENDERED/templates"

exec /bin/alertmanager \
    --config.file="$RENDERED/alertmanager.yml" \
    --storage.path=/alertmanager \
    --cluster.listen-address= \
    "$@"
