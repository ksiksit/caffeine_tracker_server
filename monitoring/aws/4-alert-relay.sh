#!/usr/bin/env bash
# 경보 4단계: Lambda caffeine-alert-relay (SNS → Slack, 하트비트 → CloudWatch) + 하트비트·Lambda 오류 알람
# 설계: docs/alerting-plan.md §5.2·§6. 절차와 확인 방법: docs/ops/경보-AWS-설정.md 4단계
#
# 먼저 할 것: 1단계(토픽)를 끝내고, Slack 수신 웹훅 URL 을 SSM SecureString /caffeine/alert-relay/slack-webhook 에 넣어 둔다.
#   웹훅 URL 은 비밀이라 이 스크립트가 받지 않는다. 없으면 넣는 방법을 출력하고 멈춘다.
#
# 만드는 것·바꾸는 것 (더하기만 하고, 다시 실행해도 된다. 코드를 고친 뒤 다시 실행하면 새 코드로 배포한다)
#   - IAM 역할 caffeine-alert-relay: 자기 로그, Caffeine/Monitoring 네임스페이스 메트릭, 웹훅 파라미터 읽기만
#   - 로그 그룹 /aws/lambda/caffeine-alert-relay (보관 14일)
#   - Lambda caffeine-alert-relay: monitoring/lambda/alert-relay 의 index.mjs·relay.mjs, nodejs22.x, arm64, VPC 없음
#   - 두 토픽 → Lambda 구독과 호출 권한
#   - 알람 caffeine-alert-relay-errors: Lambda 오류 1건 이상 (Slack 전달이 깨지면 이메일로 알린다)
#   - 알람 caffeine-heartbeat-missing: 하트비트 15분 없음. Alertmanager 가 하트비트를 보낸 적이 있을 때만 만든다 —
#     켜기 전에 만들면 바로 울린다. Alertmanager 를 켠 뒤 이 스크립트를 다시 실행하면 그때 만든다
#
# 필요한 값 (환경변수)
#   AWS_REGION  리전
#
# 예) AWS_REGION=ap-northeast-2 bash monitoring/aws/4-alert-relay.sh
source "$(dirname "$0")/common.sh"

FUNCTION=caffeine-alert-relay
ROLE=caffeine-alert-relay
PARAMETER=/caffeine/alert-relay/slack-webhook
LOG_GROUP=/aws/lambda/$FUNCTION
NAMESPACE=Caffeine/Monitoring
CODE_DIR="$HERE/../lambda/alert-relay"
ACTIONS=(--alarm-actions "$ALERT_TOPIC_ARN" --ok-actions "$ALERT_TOPIC_ARN")

log "선행 조건"
if ! aws sns get-topic-attributes --topic-arn "$ALERT_TOPIC_ARN" >/dev/null 2>&1 \
    || ! aws sns get-topic-attributes --topic-arn "$HEARTBEAT_TOPIC_ARN" >/dev/null 2>&1; then
    echo "SNS 토픽이 없다. 1단계(monitoring/aws/1-topics-and-alarms.sh)를 먼저 실행한다." >&2
    exit 1
fi
echo "  토픽 두 개 있음"
PARAMETER_TYPE=$(aws ssm describe-parameters --parameter-filters "Key=Name,Values=$PARAMETER" \
    --query 'Parameters[0].Type' --output text)
if [ "$PARAMETER_TYPE" != SecureString ]; then
    echo "SSM 파라미터 $PARAMETER (SecureString) 가 없다 (지금: $PARAMETER_TYPE). Slack 수신 웹훅 URL 을 넣고 다시 실행한다." >&2
    echo "  콘솔: Systems Manager → 파라미터 스토어 → 파라미터 생성 → 이름 $PARAMETER, 유형 SecureString, 값에 웹훅 URL" >&2
    echo "  CLI (URL 이 셸 기록에 남지 않게 입력을 숨긴다. --value 로 넘기면 CLI v1 이 https:// 값을 주소로 보고 내려받으므로 JSON 으로 넘긴다):" >&2
    echo "    read -rs URL && aws ssm put-parameter --region $AWS_REGION --cli-input-json \"{\\\"Name\\\":\\\"$PARAMETER\\\",\\\"Type\\\":\\\"SecureString\\\",\\\"Value\\\":\\\"\$URL\\\"}\"; unset URL" >&2
    exit 1
fi
echo "  웹훅 파라미터 있음 (값은 읽지 않는다)"

log "IAM 역할 $ROLE"
if aws iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
    echo "  있음"
else
    aws iam create-role --role-name "$ROLE" --assume-role-policy-document "$(cat "$HERE/policies/lambda-trust.json")" \
        --description "caffeine alert-relay Lambda - Slack relay and heartbeat metric" --query Role.Arn --output text
fi
aws iam put-role-policy --role-name "$ROLE" --policy-name caffeine-alert-relay \
    --policy-document "$(render lambda-alert-relay.json)"
ROLE_ARN=$(aws iam get-role --role-name "$ROLE" --query Role.Arn --output text)
echo "  인라인 정책 caffeine-alert-relay (자기 로그·$NAMESPACE 메트릭·웹훅 파라미터만)"

log "로그 그룹 $LOG_GROUP (보관 14일)"
if out=$(aws logs create-log-group --log-group-name "$LOG_GROUP" 2>&1); then
    echo "  만듦"
elif printf '%s' "$out" | grep -q ResourceAlreadyExistsException; then
    echo "  있음"
else
    printf '%s\n' "$out" >&2
    exit 1
fi
aws logs put-retention-policy --log-group-name "$LOG_GROUP" --retention-in-days 14

log "코드 묶기 ($CODE_DIR)"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
ZIP="$WORK/alert-relay.zip"
# 테스트 파일은 넣지 않는다. 의존성이 없어 두 파일이 전부다
if command -v zip >/dev/null 2>&1; then
    (cd "$CODE_DIR" && zip -q -X "$ZIP" index.mjs relay.mjs)
else
    (cd "$CODE_DIR" && python3 -m zipfile -c "$ZIP" index.mjs relay.mjs)
fi
echo "  $(wc -c < "$ZIP" | tr -d ' ') 바이트"

ENVIRONMENT="{\"Variables\":{\"HEARTBEAT_TOPIC_ARN\":\"$HEARTBEAT_TOPIC_ARN\",\"SLACK_WEBHOOK_PARAM\":\"$PARAMETER\",\"METRIC_NAMESPACE\":\"$NAMESPACE\"}}"
COMMON_CONFIG=(--runtime nodejs22.x --handler index.handler --role "$ROLE_ARN" --timeout 10 --memory-size 128
    --environment "$ENVIRONMENT")

log "Lambda $FUNCTION"
if aws lambda get-function --function-name "$FUNCTION" >/dev/null 2>&1; then
    aws lambda update-function-code --function-name "$FUNCTION" --zip-file "fileb://$ZIP" \
        --query 'LastUpdateStatus' --output text
    aws lambda wait function-updated --function-name "$FUNCTION"
    aws lambda update-function-configuration --function-name "$FUNCTION" "${COMMON_CONFIG[@]}" \
        --query 'LastUpdateStatus' --output text
    aws lambda wait function-updated --function-name "$FUNCTION"
    echo "  새 코드·설정으로 갱신"
else
    # 방금 만든 역할은 Lambda 가 맡을 수 있게 되기까지 몇 초 걸려 처음 몇 번은 실패할 수 있다
    for attempt in 1 2 3 4 5 6; do
        if aws lambda create-function --function-name "$FUNCTION" "${COMMON_CONFIG[@]}" \
            --architectures arm64 --zip-file "fileb://$ZIP" \
            --description "caffeine alerts: SNS to Slack, heartbeat to CloudWatch (docs/alerting-plan.md)" \
            --query 'State' --output text; then
            break
        fi
        [ "$attempt" = 6 ] && { echo "Lambda 를 만들지 못했다" >&2; exit 1; }
        echo "  IAM 반영 대기 후 다시 시도 ($attempt/6)"
        sleep 10
    done
    aws lambda wait function-active --function-name "$FUNCTION"
    echo "  만듦"
fi
FUNCTION_ARN=$(aws lambda get-function --function-name "$FUNCTION" --query 'Configuration.FunctionArn' --output text)

log "토픽 → Lambda 구독"
for topic in "$ALERT_TOPIC_ARN" "$HEARTBEAT_TOPIC_ARN"; do
    name=${topic##*:}
    if out=$(aws lambda add-permission --function-name "$FUNCTION" --statement-id "sns-$name" \
        --action lambda:InvokeFunction --principal sns.amazonaws.com --source-arn "$topic" 2>&1); then
        echo "  $name 호출 권한 추가"
    elif printf '%s' "$out" | grep -q ResourceConflictException; then
        echo "  $name 호출 권한 있음"
    else
        printf '%s\n' "$out" >&2
        exit 1
    fi
    existing=$(aws sns list-subscriptions-by-topic --topic-arn "$topic" \
        --query "Subscriptions[?Protocol=='lambda' && Endpoint=='$FUNCTION_ARN'].SubscriptionArn | [0]" --output text)
    if [ "$existing" = None ]; then
        aws sns subscribe --topic-arn "$topic" --protocol lambda --notification-endpoint "$FUNCTION_ARN" \
            --query SubscriptionArn --output text
        echo "  $name 구독 추가"
    else
        echo "  $name 구독 있음"
    fi
done

log "알람"
aws cloudwatch put-metric-alarm --alarm-name caffeine-alert-relay-errors \
    --alarm-description "경보 전달 Lambda 오류. Slack 으로 경보가 가지 않을 수 있다(이메일은 영향 없음). 경보-AWS-설정.md 4단계 문제 해결" \
    --namespace AWS/Lambda --metric-name Errors --dimensions "Name=FunctionName,Value=$FUNCTION" \
    --statistic Sum --period 300 --evaluation-periods 1 --threshold 1 \
    --comparison-operator GreaterThanOrEqualToThreshold --treat-missing-data notBreaching "${ACTIONS[@]}"
echo "  caffeine-alert-relay-errors"

HEARTBEATS=$(aws cloudwatch list-metrics --namespace "$NAMESPACE" --metric-name Heartbeat --query 'length(Metrics)' --output text)
if [ "$HEARTBEATS" != 0 ]; then
    aws cloudwatch put-metric-alarm --alarm-name caffeine-heartbeat-missing \
        --alarm-description "경보 경로 하트비트가 15분 없다. 서버 다운이 아니라 경보 경로 어딘가가 끊긴 것일 수 있다. 운영 가이드 '경보' 절 점검 순서" \
        --namespace "$NAMESPACE" --metric-name Heartbeat \
        --statistic Sum --period 300 --evaluation-periods 3 --threshold 1 \
        --comparison-operator LessThanThreshold --treat-missing-data breaching "${ACTIONS[@]}"
    echo "  caffeine-heartbeat-missing"
else
    echo "  caffeine-heartbeat-missing 은 건너뛴다 — 아직 하트비트가 온 적이 없다(지금 만들면 바로 울린다)."
    echo "  Alertmanager 를 켜고(운영 가이드 '경보' 절) 몇 분 뒤 이 스크립트를 다시 실행한다."
fi

log "완료. PC 에서 시험 메시지를 보내 Slack·이메일 둘 다 오는지 본다"
echo "  aws sns publish --region $AWS_REGION --topic-arn $ALERT_TOPIC_ARN --subject '[TEST] alert-relay' --message 'Slack 경로 확인'"
echo "  # Slack 에 안 오면 Lambda 로그 (CLI v2)"
echo "  aws logs tail $LOG_GROUP --region $AWS_REGION --since 10m"
