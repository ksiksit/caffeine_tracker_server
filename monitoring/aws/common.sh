# AWS 설정 스크립트 공통 부분. 이 폴더의 1-·2-·4- 와 infra/aws/ 의 스크립트가 source 한다 (직접 실행하지 않는다).
# 절차와 확인 방법: docs/ops/경보-AWS-설정.md
#
# PC 에서 관리자 권한 자격 증명으로 실행한다(EC2 에서 실행하지 않는다 — EC2 는 인터넷이 없고 이 권한도 없다).
# bash 3.2(macOS 기본)와 AWS CLI v1·v2 에서 돈다. jq 는 쓰지 않는다.
set -euo pipefail

export AWS_PAGER="" # CLI v2 가 결과를 less 로 열어 스크립트가 멈추지 않게

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

case "${AWS_REGION:-}" in
    "" | *[!a-z0-9-]*)
        echo "AWS_REGION 이 비었거나 형식이 틀렸다 (예: AWS_REGION=ap-northeast-2)" >&2
        exit 1
        ;;
esac
export AWS_DEFAULT_REGION="$AWS_REGION"

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ALERT_TOPIC_ARN="arn:aws:sns:$AWS_REGION:$ACCOUNT_ID:caffeine-alerts"
HEARTBEAT_TOPIC_ARN="arn:aws:sns:$AWS_REGION:$ACCOUNT_ID:caffeine-heartbeat"

log() { printf '\n== %s\n' "$*"; }

# policies/*.json 의 __REGION__·__ACCOUNT_ID__ 를 채워 출력한다
render() {
    sed -e "s/__REGION__/$AWS_REGION/g" -e "s/__ACCOUNT_ID__/$ACCOUNT_ID/g" "$HERE/policies/$1"
}

# 보안 그룹 규칙 추가. 이미 있으면 성공으로 본다 (다시 실행해도 되게)
allow_duplicate() {
    local out
    if out=$("$@" 2>&1); then
        echo "  추가함"
    elif printf '%s' "$out" | grep -q 'InvalidPermission.Duplicate'; then
        echo "  이미 있음"
    else
        printf '%s\n' "$out" >&2
        return 1
    fi
}

echo "계정 $ACCOUNT_ID · 리전 $AWS_REGION"
