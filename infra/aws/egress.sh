#!/usr/bin/env bash
# EC2(caffeine-ec2 보안 그룹)에서 인터넷으로 나가는 길을 닫거나 잠깐 연다.
# 절차: docs/ops/기반-AWS-설정.md
#
#   close   인터넷으로 나가는 규칙을 지운다. 남는 것: RDS(3306)와, 경보 2단계가 추가한 SNS 엔드포인트(443)뿐.
#           들어오는 SSH·API 와 그 응답은 그대로 된다(보안 그룹은 들어온 연결의 응답을 따로 허용할 필요가 없다).
#   open    443 만 연다. OS 패치(sudo dnf upgrade)나 Docker 갱신처럼 EC2 가 직접 받아야 할 때만 쓰고 끝나면 close.
#   status  지금 나가는 규칙을 보여 준다.
# 다시 실행해도 된다.
#
# 필요한 값 (환경변수)
#   AWS_REGION  리전
#
# 예) AWS_REGION=ap-northeast-2 bash infra/aws/egress.sh close
source "$(cd "$(dirname "$0")" && pwd)/../../monitoring/aws/common.sh"

ACTION=${1:-}
ALL="IpProtocol=-1,IpRanges=[{CidrIp=0.0.0.0/0}]"                      # 보안 그룹을 만들면 기본으로 붙는 전체 허용
HTTPS="IpProtocol=tcp,FromPort=443,ToPort=443,IpRanges=[{CidrIp=0.0.0.0/0}]" # open 이 여는 규칙

EC2_SG=$(aws ec2 describe-security-groups --filters "Name=group-name,Values=caffeine-ec2" \
    --query 'SecurityGroups[0].GroupId' --output text)
if [ "$EC2_SG" = None ]; then
    echo "보안 그룹 caffeine-ec2 가 없다. infra/aws/base.sh 를 먼저 실행한다." >&2
    exit 1
fi

# 규칙 삭제. 이미 없으면 성공으로 본다
revoke() {
    local out
    if out=$(aws ec2 revoke-security-group-egress --group-id "$EC2_SG" --ip-permissions "$1" 2>&1); then
        echo "  지움: $2"
    elif printf '%s' "$out" | grep -q 'InvalidPermission.NotFound'; then
        echo "  이미 없음: $2"
    else
        printf '%s\n' "$out" >&2
        return 1
    fi
}

show() {
    log "caffeine-ec2 ($EC2_SG) 에서 나가는 규칙"
    aws ec2 describe-security-groups --group-ids "$EC2_SG" --output text --query \
        'SecurityGroups[0].IpPermissionsEgress[].[IpProtocol,FromPort,ToPort,join(`,`,IpRanges[].CidrIp),join(`,`,UserIdGroupPairs[].GroupId)]'
    echo "  (프로토콜 · 포트 · 대상 주소 · 대상 보안 그룹. 대상 주소에 0.0.0.0/0 이 없으면 인터넷으로 나가지 못한다)"
}

case "$ACTION" in
    close)
        log "나가는 길 닫기"
        revoke "$ALL" "전체 허용"
        revoke "$HTTPS" "443 허용"
        show
        ;;
    open)
        log "443 만 잠깐 열기 — 끝나면 close"
        allow_duplicate aws ec2 authorize-security-group-egress --group-id "$EC2_SG" --ip-permissions "$HTTPS"
        show
        ;;
    status)
        show
        ;;
    *)
        echo "쓰는 법: AWS_REGION=<리전> bash infra/aws/egress.sh close|open|status" >&2
        exit 1
        ;;
esac
