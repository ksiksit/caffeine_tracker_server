#!/usr/bin/env bash
# 경보 2단계: EC2(인터넷 없음) → SNS 경로 (docs/alerting-plan.md §7, §11 2단계)
# 절차와 확인 방법: docs/ops/경보-AWS-설정.md
#
# 만드는 것·바꾸는 것 (모두 더하기만 하고 지우지 않는다)
#   - 보안 그룹 caffeine-sns-endpoint: EC2 의 보안 그룹에서 오는 443 만 받는다
#   - EC2 의 보안 그룹: 위 보안 그룹으로 나가는 443 규칙 추가 (인터넷으로 나가는 길은 열지 않는다)
#   - SNS 인터페이스 엔드포인트: EC2 서브넷 하나, 프라이빗 DNS 켬, 정책은 두 토픽 발행만
#   - EC2 인스턴스 역할: 없으면 caffeine-ec2 역할을 만들어 붙이고, 있으면 그 역할에 인라인 정책 caffeine-sns-publish 추가
#   - 인스턴스 메타데이터 홉 제한 2: 컨테이너(Alertmanager)가 인스턴스 역할 자격 증명을 받으려면 필요하다
# 다시 실행해도 된다. VPC 의 DNS 설정이 꺼져 있으면 바꾸지 않고 멈춘다(다른 서비스에도 영향이 있어 직접 판단한다).
#
# 필요한 값 (환경변수)
#   AWS_REGION       리전
#   EC2_INSTANCE_ID  운영 EC2 (i-...)
#
# 예) AWS_REGION=ap-northeast-2 EC2_INSTANCE_ID=i-0123 bash monitoring/aws/2-ec2-to-sns.sh
source "$(dirname "$0")/common.sh"

: "${EC2_INSTANCE_ID:?EC2_INSTANCE_ID 가 필요하다 (i-...)}"
SERVICE_NAME="com.amazonaws.$AWS_REGION.sns"
AWS_CLI_IMAGE=amazon/aws-cli:2.22.0 # EC2 에서 경로를 확인할 때 쓰는 이미지 (docs/ops/경보-AWS-설정.md 와 같게)

log "EC2 정보 ($EC2_INSTANCE_ID)"
read -r VPC_ID SUBNET_ID PROFILE_ARN < <(aws ec2 describe-instances --instance-ids "$EC2_INSTANCE_ID" \
    --query 'Reservations[0].Instances[0].[VpcId,SubnetId,IamInstanceProfile.Arn]' --output text)
INSTANCE_SGS=$(aws ec2 describe-instances --instance-ids "$EC2_INSTANCE_ID" \
    --query 'Reservations[0].Instances[0].SecurityGroups[].GroupId' --output text)
case "${VPC_ID:-None}" in
    vpc-*) ;;
    *) echo "EC2 $EC2_INSTANCE_ID 의 VPC 를 찾지 못했다 (인스턴스 ID·리전 확인)" >&2; exit 1 ;;
esac
echo "  VPC $VPC_ID · 서브넷 $SUBNET_ID · 보안 그룹 $INSTANCE_SGS · 인스턴스 프로필 $PROFILE_ARN"

log "VPC DNS 설정 (프라이빗 DNS 엔드포인트의 전제)"
DNS_SUPPORT=$(aws ec2 describe-vpc-attribute --vpc-id "$VPC_ID" --attribute enableDnsSupport \
    --query 'EnableDnsSupport.Value' --output text)
DNS_HOSTNAMES=$(aws ec2 describe-vpc-attribute --vpc-id "$VPC_ID" --attribute enableDnsHostnames \
    --query 'EnableDnsHostnames.Value' --output text)
echo "  enableDnsSupport=$DNS_SUPPORT · enableDnsHostnames=$DNS_HOSTNAMES"
if [ "$DNS_SUPPORT" != True ] || [ "$DNS_HOSTNAMES" != True ]; then
    echo "VPC 의 DNS 설정이 꺼져 있어 프라이빗 DNS 엔드포인트를 만들 수 없다. 이 스크립트는 VPC 설정을 바꾸지 않는다." >&2
    echo "같은 VPC 의 다른 서비스에 영향이 없는지 확인한 뒤 직접 켜고 다시 실행한다:" >&2
    echo "  aws ec2 modify-vpc-attribute --vpc-id $VPC_ID --enable-dns-support '{\"Value\":true}'" >&2
    echo "  aws ec2 modify-vpc-attribute --vpc-id $VPC_ID --enable-dns-hostnames '{\"Value\":true}'" >&2
    exit 1
fi

log "엔드포인트 보안 그룹 caffeine-sns-endpoint"
ENDPOINT_SG=$(aws ec2 describe-security-groups \
    --filters "Name=vpc-id,Values=$VPC_ID" "Name=group-name,Values=caffeine-sns-endpoint" \
    --query 'SecurityGroups[0].GroupId' --output text)
if [ "$ENDPOINT_SG" = None ]; then
    ENDPOINT_SG=$(aws ec2 create-security-group --vpc-id "$VPC_ID" --group-name caffeine-sns-endpoint \
        --description "SNS interface endpoint for caffeine alerts (443 from app EC2 only)" \
        --query GroupId --output text)
    echo "  만듦 $ENDPOINT_SG"
else
    echo "  있음 $ENDPOINT_SG"
fi
for sg in $INSTANCE_SGS; do
    echo "  인바운드 443 ← $sg"
    allow_duplicate aws ec2 authorize-security-group-ingress --group-id "$ENDPOINT_SG" \
        --ip-permissions "IpProtocol=tcp,FromPort=443,ToPort=443,UserIdGroupPairs=[{GroupId=$sg}]"
    # EC2 보안 그룹의 아웃바운드가 막혀 있어도 엔드포인트까지는 가게 한다. 대상이 이 보안 그룹뿐이라 인터넷은 그대로 막힌다
    echo "  $sg 아웃바운드 443 → $ENDPOINT_SG"
    allow_duplicate aws ec2 authorize-security-group-egress --group-id "$sg" \
        --ip-permissions "IpProtocol=tcp,FromPort=443,ToPort=443,UserIdGroupPairs=[{GroupId=$ENDPOINT_SG}]"
done

log "SNS 인터페이스 엔드포인트 ($SERVICE_NAME)"
ENDPOINT_ID=$(aws ec2 describe-vpc-endpoints \
    --filters "Name=vpc-id,Values=$VPC_ID" "Name=service-name,Values=$SERVICE_NAME" \
    "Name=vpc-endpoint-state,Values=pendingAcceptance,pending,available" \
    --query 'VpcEndpoints[0].VpcEndpointId' --output text)
if [ "$ENDPOINT_ID" = None ]; then
    ENDPOINT_ID=$(aws ec2 create-vpc-endpoint --vpc-id "$VPC_ID" --vpc-endpoint-type Interface \
        --service-name "$SERVICE_NAME" --subnet-ids "$SUBNET_ID" --security-group-ids "$ENDPOINT_SG" \
        --private-dns-enabled --policy-document "$(render sns-endpoint.json)" \
        --query 'VpcEndpoint.VpcEndpointId' --output text)
    echo "  만듦 $ENDPOINT_ID (available 까지 몇 분 걸린다)"
else
    # 이미 있는 엔드포인트는 다른 서비스도 쓰고 있을 수 있어 정책·보안 그룹을 바꾸지 않는다
    echo "  이미 있음 $ENDPOINT_ID — 바꾸지 않는다. 다음 두 가지를 직접 확인한다:"
    echo "    - 엔드포인트 정책이 두 토픽의 sns:Publish 를 허용하는가 (policies/sns-endpoint.json)"
    echo "    - 엔드포인트 보안 그룹이 EC2 보안 그룹($INSTANCE_SGS)에서 오는 443 을 받는가"
fi

log "EC2 인스턴스 역할"
if [ "$PROFILE_ARN" = None ]; then
    if aws iam get-role --role-name caffeine-ec2 >/dev/null 2>&1; then
        echo "  역할 caffeine-ec2 있음"
    else
        aws iam create-role --role-name caffeine-ec2 --assume-role-policy-document "$(cat "$HERE/policies/ec2-trust.json")" \
            --description "caffeine app EC2 - publish alerts to SNS" --query Role.Arn --output text
    fi
    if aws iam get-instance-profile --instance-profile-name caffeine-ec2 >/dev/null 2>&1; then
        echo "  인스턴스 프로필 caffeine-ec2 있음"
    else
        aws iam create-instance-profile --instance-profile-name caffeine-ec2 --query InstanceProfile.Arn --output text
    fi
    PROFILE_ROLES=$(aws iam get-instance-profile --instance-profile-name caffeine-ec2 \
        --query 'InstanceProfile.Roles[].RoleName' --output text)
    case " $PROFILE_ROLES " in
        *" caffeine-ec2 "*) ;;
        *) aws iam add-role-to-instance-profile --instance-profile-name caffeine-ec2 --role-name caffeine-ec2 ;;
    esac
    # 새 인스턴스 프로필은 IAM 에 퍼지는 데 몇 초 걸려 처음 몇 번은 실패할 수 있다
    for attempt in 1 2 3 4 5 6; do
        if aws ec2 associate-iam-instance-profile --instance-id "$EC2_INSTANCE_ID" \
            --iam-instance-profile Name=caffeine-ec2 --query 'IamInstanceProfileAssociation.State' --output text; then
            break
        fi
        [ "$attempt" = 6 ] && { echo "인스턴스 프로필을 붙이지 못했다" >&2; exit 1; }
        echo "  IAM 반영 대기 후 다시 시도 ($attempt/6)"
        sleep 10
    done
    ROLE_NAME=caffeine-ec2
else
    ROLE_NAME=$(aws iam get-instance-profile --instance-profile-name "${PROFILE_ARN##*/}" \
        --query 'InstanceProfile.Roles[0].RoleName' --output text)
    echo "  이미 붙은 역할 $ROLE_NAME 을 쓴다 (인라인 정책만 추가)"
fi
aws iam put-role-policy --role-name "$ROLE_NAME" --policy-name caffeine-sns-publish \
    --policy-document "$(render ec2-sns-publish.json)"
echo "  $ROLE_NAME 에 caffeine-sns-publish (두 토픽 sns:Publish 만)"

log "인스턴스 메타데이터 홉 제한"
HOP_LIMIT=$(aws ec2 describe-instances --instance-ids "$EC2_INSTANCE_ID" \
    --query 'Reservations[0].Instances[0].MetadataOptions.HttpPutResponseHopLimit' --output text)
case "$HOP_LIMIT" in
    "" | None | *[!0-9]* | 0 | 1)
        aws ec2 modify-instance-metadata-options --instance-id "$EC2_INSTANCE_ID" \
            --http-put-response-hop-limit 2 --http-endpoint enabled \
            --query 'InstanceMetadataOptions.HttpPutResponseHopLimit' --output text
        echo "  $HOP_LIMIT → 2 (재부팅 없이 바로 적용된다)"
        ;;
    *)
        echo "  이미 $HOP_LIMIT"
        ;;
esac

log "완료. 엔드포인트가 available 이 된 뒤 EC2 에서 확인한다 (docs/ops/경보-AWS-설정.md 2단계 확인)"
echo "  aws ec2 describe-vpc-endpoints --vpc-endpoint-ids $ENDPOINT_ID --query 'VpcEndpoints[0].State' --output text"
echo
echo "  # EC2: 사설 IP(10.x · 172.16~31.x · 192.168.x)로 풀려야 한다"
echo "  getent hosts sns.$AWS_REGION.amazonaws.com"
echo "  # EC2: 컨테이너에서 인스턴스 역할로 발행 — 메일이 오면 경로 전체가 된다"
echo "  docker run --rm $AWS_CLI_IMAGE sns publish --region $AWS_REGION --topic-arn $ALERT_TOPIC_ARN \\"
echo "    --subject '[TEST] ec2-to-sns' --message 'EC2 컨테이너 → SNS 엔드포인트 경로 확인'"
