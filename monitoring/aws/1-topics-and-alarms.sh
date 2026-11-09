#!/usr/bin/env bash
# 경보 1단계: SNS 토픽 두 개 + 이메일 구독 + EC2·RDS 인프라 알람 (docs/alerting-plan.md §5.1, §11 1단계)
# 절차와 확인 방법: docs/ops/경보-AWS-설정.md
#
# EC2·RDS 의 설정은 바꾸지 않는다. CloudWatch 가 바깥에서 지켜보는 알람만 만든다.
# 다시 실행해도 된다: 토픽은 있으면 그대로, 알람은 같은 이름으로 덮어쓰고, 구독은 확인된 것이 있으면 건너뛴다.
#
# 필요한 값 (환경변수)
#   AWS_REGION        리전 (예: ap-northeast-2)
#   ALERT_EMAIL       경보를 받을 이메일
#   EC2_INSTANCE_ID   운영 EC2 (i-...)
#   RDS_INSTANCE_ID   운영 RDS 식별자. 비우면 RDS 알람을 만들지 않는다
#   EC2_AUTO_RECOVER  yes 면 시스템 상태 검사 실패 때 인스턴스를 자동 복구하는 알람도 만든다 (기본 no)
#
# 예) AWS_REGION=ap-northeast-2 ALERT_EMAIL=me@example.com EC2_INSTANCE_ID=i-0123 RDS_INSTANCE_ID=caffeine-db \
#       bash monitoring/aws/1-topics-and-alarms.sh
source "$(dirname "$0")/common.sh"

: "${ALERT_EMAIL:?ALERT_EMAIL 이 필요하다 (경보를 받을 이메일)}"
: "${EC2_INSTANCE_ID:?EC2_INSTANCE_ID 가 필요하다 (i-...)}"
RDS_INSTANCE_ID=${RDS_INSTANCE_ID:-}
EC2_AUTO_RECOVER=${EC2_AUTO_RECOVER:-no}

ACTIONS=(--alarm-actions "$ALERT_TOPIC_ARN" --ok-actions "$ALERT_TOPIC_ARN") # 울릴 때와 풀릴 때 모두 알린다

log "SNS 토픽 (암호화하지 않는다 — CloudWatch 알람은 AWS 관리형 키로 암호화된 토픽에 발행하지 못하고, 메시지에 개인정보가 없다)"
aws sns create-topic --name caffeine-alerts --query TopicArn --output text
aws sns create-topic --name caffeine-heartbeat --query TopicArn --output text
# 계정 관리 권한 + CloudWatch 알람의 발행 권한(같은 계정에서 온 것만)
aws sns set-topic-attributes --topic-arn "$ALERT_TOPIC_ARN" \
    --attribute-name Policy --attribute-value "$(render sns-topic-alerts.json)"
echo "  caffeine-alerts 접근 정책 적용"

log "이메일 구독 ($ALERT_EMAIL → caffeine-alerts)"
SUBSCRIPTION=$(aws sns list-subscriptions-by-topic --topic-arn "$ALERT_TOPIC_ARN" \
    --query "Subscriptions[?Protocol=='email' && Endpoint=='$ALERT_EMAIL'].SubscriptionArn | [0]" --output text)
case "$SUBSCRIPTION" in
    arn:*)
        echo "  이미 확인된 구독: $SUBSCRIPTION"
        ;;
    *)
        # 없거나 확인 대기(PendingConfirmation)면 다시 요청한다 — 확인 메일을 다시 보낸다
        aws sns subscribe --topic-arn "$ALERT_TOPIC_ARN" --protocol email --notification-endpoint "$ALERT_EMAIL" \
            --query SubscriptionArn --output text
        echo "  >>> $ALERT_EMAIL 메일함의 'AWS Notification - Subscription Confirmation' 메일에서 Confirm subscription 을 누른다"
        ;;
esac

log "EC2 알람 ($EC2_INSTANCE_ID)"
INSTANCE_TYPE=$(aws ec2 describe-instances --instance-ids "$EC2_INSTANCE_ID" \
    --query 'Reservations[0].Instances[0].InstanceType' --output text)
echo "  인스턴스 유형 $INSTANCE_TYPE"

# 상태 검사 지표는 1분 단위로 무료 제공된다. 인스턴스를 일부러 멈추면 지표가 끊겨 '데이터 부족'이 된다 — 그 경우는 하트비트 알람이 맡는다
aws cloudwatch put-metric-alarm --alarm-name caffeine-ec2-status-check \
    --alarm-description "운영 EC2 상태 검사 실패 2분. 인스턴스가 멈췄거나 응답하지 않는다. 운영 가이드 '경보' 절" \
    --namespace AWS/EC2 --metric-name StatusCheckFailed --dimensions "Name=InstanceId,Value=$EC2_INSTANCE_ID" \
    --statistic Maximum --period 60 --evaluation-periods 2 --threshold 1 \
    --comparison-operator GreaterThanOrEqualToThreshold "${ACTIONS[@]}"
echo "  caffeine-ec2-status-check"

if [ "$EC2_AUTO_RECOVER" = yes ]; then
    # AWS 쪽 하드웨어 문제(시스템 상태 검사)일 때만 같은 설정으로 다른 호스트에서 다시 띄운다. 인스턴스 안의 문제는 대상이 아니다
    aws cloudwatch put-metric-alarm --alarm-name caffeine-ec2-auto-recover \
        --alarm-description "운영 EC2 시스템 상태 검사 실패 2분 — 인스턴스 자동 복구를 실행했다" \
        --namespace AWS/EC2 --metric-name StatusCheckFailed_System --dimensions "Name=InstanceId,Value=$EC2_INSTANCE_ID" \
        --statistic Maximum --period 60 --evaluation-periods 2 --threshold 1 \
        --comparison-operator GreaterThanOrEqualToThreshold \
        --alarm-actions "arn:aws:automate:$AWS_REGION:ec2:recover" "$ALERT_TOPIC_ARN" --ok-actions "$ALERT_TOPIC_ARN"
    echo "  caffeine-ec2-auto-recover"
fi

case "$INSTANCE_TYPE" in
    t*)
        # 버스트형(t 계열)은 크레딧이 바닥나면 기준 성능으로 묶여 응답이 느려진다
        aws cloudwatch put-metric-alarm --alarm-name caffeine-ec2-cpu-credit \
            --alarm-description "운영 EC2 CPU 크레딧 20 미만 15분. 곧 기준 성능으로 묶여 느려진다" \
            --namespace AWS/EC2 --metric-name CPUCreditBalance --dimensions "Name=InstanceId,Value=$EC2_INSTANCE_ID" \
            --statistic Minimum --period 300 --evaluation-periods 3 --threshold 20 \
            --comparison-operator LessThanThreshold "${ACTIONS[@]}"
        echo "  caffeine-ec2-cpu-credit (버스트형)"
        ;;
esac

if [ -n "$RDS_INSTANCE_ID" ]; then
    log "RDS 알람 ($RDS_INSTANCE_ID)"
    aws cloudwatch put-metric-alarm --alarm-name caffeine-rds-free-storage \
        --alarm-description "운영 RDS 여유 저장공간 2GiB 미만 15분. 가득 차면 쓰기가 실패한다" \
        --namespace AWS/RDS --metric-name FreeStorageSpace --dimensions "Name=DBInstanceIdentifier,Value=$RDS_INSTANCE_ID" \
        --statistic Minimum --period 300 --evaluation-periods 3 --threshold 2147483648 \
        --comparison-operator LessThanThreshold "${ACTIONS[@]}"
    echo "  caffeine-rds-free-storage"
    aws cloudwatch put-metric-alarm --alarm-name caffeine-rds-cpu \
        --alarm-description "운영 RDS CPU 80% 초과 15분. 느린 쿼리나 과부하" \
        --namespace AWS/RDS --metric-name CPUUtilization --dimensions "Name=DBInstanceIdentifier,Value=$RDS_INSTANCE_ID" \
        --statistic Average --period 300 --evaluation-periods 3 --threshold 80 \
        --comparison-operator GreaterThanThreshold "${ACTIONS[@]}"
    echo "  caffeine-rds-cpu"
else
    log "RDS_INSTANCE_ID 가 비어 RDS 알람은 건너뛴다"
fi

log "완료. 운영 EC2 의 .env 에 넣을 값 (Alertmanager 는 2단계 확인 뒤에 켠다)"
echo "ALERT_TOPIC_ARN=$ALERT_TOPIC_ARN"
echo "HEARTBEAT_TOPIC_ARN=$HEARTBEAT_TOPIC_ARN"
echo "AWS_REGION=$AWS_REGION"
echo
echo "확인: 이메일 구독을 승인한 뒤 알람 하나를 강제로 울려 메일이 오는지 본다 (다음 평가 때 실제 상태로 돌아온다)"
echo "  aws cloudwatch set-alarm-state --alarm-name caffeine-ec2-status-check --state-value ALARM --state-reason '경로 확인'"
