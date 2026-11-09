#!/usr/bin/env bash
# 기반 인프라: VPC · 서브넷 · 보안 그룹 · 키 페어 · EC2(고정 IP) · RDS(MySQL)
# 절차와 확인 방법, 되돌리기: docs/ops/기반-AWS-설정.md
#
# 만드는 것 (더하기만 하고 지우지 않는다. 다시 실행하면 이름이 같은 것은 건너뛴다)
#   - VPC caffeine (10.20.0.0/16, DNS 켬) + 인터넷 게이트웨이
#   - 서브넷 caffeine-public(EC2) · caffeine-db-a · caffeine-db-b(RDS, 인터넷 경로 없음)
#   - 보안 그룹 caffeine-ec2: 들어오는 22(SSH_CIDR)·8080(API_CIDR). caffeine-rds: caffeine-ec2 에서 오는 3306 만
#   - 키 페어: 없으면 만들어 ~/.ssh/<KEY_NAME>.pem 에 저장
#   - EC2 caffeine-app: Amazon Linux 2023 x86_64, 디스크 20GB, 고정 IP. 첫 부팅 때 Docker 설치(user-data.sh)
#   - RDS caffeine-db: MySQL, 비공개, 초기 DB caffeine_tracker, 사용자 caffeine
# EC2 에서 나가는 길은 Docker 설치를 위해 열린 채로 만들어진다. 설치를 확인한 뒤 egress.sh close 로 닫는다.
#
# 필요한 값 (환경변수)
#   AWS_REGION         리전 (예: ap-northeast-2)
#   SSH_CIDR           SSH 를 허용할 내 PC 주소 (예: 203.0.113.7/32). 확인: curl -s https://checkip.amazonaws.com
#   DB_PASSWORD        RDS 비밀번호. 영문·숫자 16~41자 (생성: openssl rand -hex 16). RDS 가 이미 있으면 필요 없다
#   API_CIDR           API(8080)를 허용할 주소 (기본 0.0.0.0/0 — 앱이 어디서나 접속)
#   KEY_NAME           키 페어 이름 (기본 caffeine-key)
#   INSTANCE_TYPE      EC2 유형 (기본 t3.small). CI 가 amd64 이미지만 만들므로 x86_64 유형만 쓴다 (t4g 등 arm 은 안 된다)
#   DB_INSTANCE_CLASS  RDS 유형 (기본 db.t4g.micro)
#   DB_ENGINE_VERSION  MySQL 버전 (기본 8.4). 8.0 은 2026-07-31 표준 지원이 끝나 연장 지원 요금이 붙으므로 거부되게 해 두었다
#
# 예) read -rs DB_PASSWORD && export DB_PASSWORD
#     AWS_REGION=ap-northeast-2 SSH_CIDR=$(curl -s https://checkip.amazonaws.com)/32 bash infra/aws/base.sh
BASE_DIR=$(cd "$(dirname "$0")" && pwd)
source "$BASE_DIR/../../monitoring/aws/common.sh"

: "${SSH_CIDR:?SSH_CIDR 이 필요하다 (내 PC 주소, 예: 203.0.113.7/32)}"
API_CIDR=${API_CIDR:-0.0.0.0/0}
KEY_NAME=${KEY_NAME:-caffeine-key}
INSTANCE_TYPE=${INSTANCE_TYPE:-t3.small}
DB_INSTANCE_CLASS=${DB_INSTANCE_CLASS:-db.t4g.micro}
DB_ENGINE_VERSION=${DB_ENGINE_VERSION:-8.4}
DB_ID=caffeine-db
DB_NAME=caffeine_tracker
DB_USER=caffeine

for cidr in "$SSH_CIDR" "$API_CIDR"; do
    if ! printf '%s' "$cidr" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}/[0-9]{1,2}$'; then
        echo "주소 형식이 틀렸다: $cidr (예: 203.0.113.7/32)" >&2
        exit 1
    fi
done
case "$SSH_CIDR" in
    */0) echo "SSH_CIDR 을 전체(/0)로 열지 않는다. 내 PC 주소를 /32 로 준다." >&2; exit 1 ;;
esac

if aws rds describe-db-instances --db-instance-identifier "$DB_ID" >/dev/null 2>&1; then
    RDS_EXISTS=yes
else
    RDS_EXISTS=no
    : "${DB_PASSWORD:?DB_PASSWORD 가 필요하다 (영문·숫자 16~41자, 생성: openssl rand -hex 16)}"
    # 특수문자는 RDS(/ @ \" 공백 금지)·.env·compose 변수 치환에서 각각 다르게 깨지므로 받지 않는다
    case "$DB_PASSWORD" in
        *[!A-Za-z0-9]*) echo "DB_PASSWORD 는 영문·숫자만 쓴다 (생성: openssl rand -hex 16)" >&2; exit 1 ;;
    esac
    if [ "${#DB_PASSWORD}" -lt 16 ] || [ "${#DB_PASSWORD}" -gt 41 ]; then
        echo "DB_PASSWORD 는 16~41자여야 한다 (지금 ${#DB_PASSWORD}자)" >&2
        exit 1
    fi
fi

name_tag() { echo "ResourceType=$1,Tags=[{Key=Name,Value=$2}]"; }

log "VPC caffeine"
VPC_ID=$(aws ec2 describe-vpcs --filters "Name=tag:Name,Values=caffeine" --query 'Vpcs[0].VpcId' --output text)
if [ "$VPC_ID" = None ]; then
    VPC_ID=$(aws ec2 create-vpc --cidr-block 10.20.0.0/16 --tag-specifications "$(name_tag vpc caffeine)" \
        --query Vpc.VpcId --output text)
    echo "  만듦 $VPC_ID"
else
    echo "  있음 $VPC_ID"
fi
# 둘 다 켜야 RDS 주소가 풀리고, 경보 2단계의 프라이빗 DNS 엔드포인트도 만들 수 있다 (새 VPC 는 hostnames 가 꺼져 있다)
aws ec2 modify-vpc-attribute --vpc-id "$VPC_ID" --enable-dns-support '{"Value":true}'
aws ec2 modify-vpc-attribute --vpc-id "$VPC_ID" --enable-dns-hostnames '{"Value":true}'

log "인터넷 게이트웨이 (들어오는 SSH·API 용. 나가는 길은 보안 그룹으로 막는다)"
IGW_ID=$(aws ec2 describe-internet-gateways --filters "Name=tag:Name,Values=caffeine" \
    --query 'InternetGateways[0].InternetGatewayId' --output text)
if [ "$IGW_ID" = None ]; then
    IGW_ID=$(aws ec2 create-internet-gateway --tag-specifications "$(name_tag internet-gateway caffeine)" \
        --query InternetGateway.InternetGatewayId --output text)
    echo "  만듦 $IGW_ID"
else
    echo "  있음 $IGW_ID"
fi
ATTACHED_VPC=$(aws ec2 describe-internet-gateways --internet-gateway-ids "$IGW_ID" \
    --query 'InternetGateways[0].Attachments[0].VpcId' --output text)
if [ "$ATTACHED_VPC" = None ]; then
    aws ec2 attach-internet-gateway --internet-gateway-id "$IGW_ID" --vpc-id "$VPC_ID"
    echo "  VPC 에 연결"
fi

log "서브넷"
# 이름 CIDR 가용영역 → SUBNET_ID
ensure_subnet() {
    SUBNET_ID=$(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$VPC_ID" "Name=tag:Name,Values=$1" \
        --query 'Subnets[0].SubnetId' --output text)
    if [ "$SUBNET_ID" = None ]; then
        SUBNET_ID=$(aws ec2 create-subnet --vpc-id "$VPC_ID" --cidr-block "$2" --availability-zone "$3" \
            --tag-specifications "$(name_tag subnet "$1")" --query Subnet.SubnetId --output text)
        echo "  $1 만듦 $SUBNET_ID ($2 · $3)"
    else
        echo "  $1 있음 $SUBNET_ID"
    fi
}
# EC2 유형을 쓸 수 있는 가용영역을 고른다 (리전마다 일부 영역은 특정 유형이 없다)
EC2_AZ=$(aws ec2 describe-instance-type-offerings --location-type availability-zone \
    --filters "Name=instance-type,Values=$INSTANCE_TYPE" --query 'sort(InstanceTypeOfferings[].Location)[0]' --output text)
case "$EC2_AZ" in
    "" | None) echo "이 리전에 EC2 유형 $INSTANCE_TYPE 이 없다" >&2; exit 1 ;;
esac
ensure_subnet caffeine-public 10.20.1.0/24 "$EC2_AZ"
PUBLIC_SUBNET=$SUBNET_ID
PUBLIC_AZ=$(aws ec2 describe-subnets --subnet-ids "$PUBLIC_SUBNET" --query 'Subnets[0].AvailabilityZone' --output text)
# RDS 서브넷 그룹은 가용영역 두 곳이 필요하다. RDS 자체는 EC2 와 같은 영역에 둔다(영역 간 전송 요금·지연 없음)
OTHER_AZ=$(aws ec2 describe-availability-zones --filters "Name=state,Values=available" \
    --query "sort(AvailabilityZones[?ZoneName!='$PUBLIC_AZ'].ZoneName)[0]" --output text)
ensure_subnet caffeine-db-a 10.20.11.0/24 "$PUBLIC_AZ"
DB_SUBNET_A=$SUBNET_ID
ensure_subnet caffeine-db-b 10.20.12.0/24 "$OTHER_AZ"
DB_SUBNET_B=$SUBNET_ID

log "라우팅 (caffeine-public 만 인터넷 게이트웨이로. DB 서브넷은 VPC 안에서만 통한다)"
RTB_ID=$(aws ec2 describe-route-tables --filters "Name=vpc-id,Values=$VPC_ID" "Name=tag:Name,Values=caffeine-public" \
    --query 'RouteTables[0].RouteTableId' --output text)
if [ "$RTB_ID" = None ]; then
    RTB_ID=$(aws ec2 create-route-table --vpc-id "$VPC_ID" --tag-specifications "$(name_tag route-table caffeine-public)" \
        --query RouteTable.RouteTableId --output text)
    echo "  만듦 $RTB_ID"
else
    echo "  있음 $RTB_ID"
fi
DEFAULT_ROUTES=$(aws ec2 describe-route-tables --route-table-ids "$RTB_ID" \
    --query "length(RouteTables[0].Routes[?DestinationCidrBlock=='0.0.0.0/0'])" --output text)
if [ "$DEFAULT_ROUTES" = 0 ]; then
    aws ec2 create-route --route-table-id "$RTB_ID" --destination-cidr-block 0.0.0.0/0 --gateway-id "$IGW_ID" >/dev/null
    echo "  0.0.0.0/0 → $IGW_ID"
fi
ASSOCIATIONS=$(aws ec2 describe-route-tables --route-table-ids "$RTB_ID" \
    --query "length(RouteTables[0].Associations[?SubnetId=='$PUBLIC_SUBNET'])" --output text)
if [ "$ASSOCIATIONS" = 0 ]; then
    aws ec2 associate-route-table --route-table-id "$RTB_ID" --subnet-id "$PUBLIC_SUBNET" >/dev/null
    echo "  caffeine-public 에 연결"
fi

log "보안 그룹"
# 이름 설명 → SG_ID
ensure_sg() {
    SG_ID=$(aws ec2 describe-security-groups --filters "Name=vpc-id,Values=$VPC_ID" "Name=group-name,Values=$1" \
        --query 'SecurityGroups[0].GroupId' --output text)
    if [ "$SG_ID" = None ]; then
        SG_ID=$(aws ec2 create-security-group --vpc-id "$VPC_ID" --group-name "$1" --description "$2" \
            --tag-specifications "$(name_tag security-group "$1")" --query GroupId --output text)
        echo "  $1 만듦 $SG_ID"
    else
        echo "  $1 있음 $SG_ID"
    fi
}
ensure_sg caffeine-ec2 "caffeine app EC2 (ssh from admin, api 8080)"
EC2_SG=$SG_ID
ensure_sg caffeine-rds "caffeine RDS (3306 from app EC2 only)"
RDS_SG=$SG_ID
echo "  caffeine-ec2 인바운드 22 ← $SSH_CIDR"
allow_duplicate aws ec2 authorize-security-group-ingress --group-id "$EC2_SG" \
    --ip-permissions "IpProtocol=tcp,FromPort=22,ToPort=22,IpRanges=[{CidrIp=$SSH_CIDR}]"
echo "  caffeine-ec2 인바운드 8080 ← $API_CIDR"
allow_duplicate aws ec2 authorize-security-group-ingress --group-id "$EC2_SG" \
    --ip-permissions "IpProtocol=tcp,FromPort=8080,ToPort=8080,IpRanges=[{CidrIp=$API_CIDR}]"
echo "  caffeine-rds 인바운드 3306 ← caffeine-ec2"
allow_duplicate aws ec2 authorize-security-group-ingress --group-id "$RDS_SG" \
    --ip-permissions "IpProtocol=tcp,FromPort=3306,ToPort=3306,UserIdGroupPairs=[{GroupId=$EC2_SG}]"
# 나가는 길을 닫은 뒤에도 DB 로는 가야 한다. 지금은 전체 허용 규칙과 겹치지만 미리 넣어 둔다
echo "  caffeine-ec2 아웃바운드 3306 → caffeine-rds"
allow_duplicate aws ec2 authorize-security-group-egress --group-id "$EC2_SG" \
    --ip-permissions "IpProtocol=tcp,FromPort=3306,ToPort=3306,UserIdGroupPairs=[{GroupId=$RDS_SG}]"

log "RDS 서브넷 그룹·인스턴스 ($DB_ID · MySQL $DB_ENGINE_VERSION · $DB_INSTANCE_CLASS)"
if aws rds describe-db-subnet-groups --db-subnet-group-name "$DB_ID" >/dev/null 2>&1; then
    echo "  서브넷 그룹 있음"
else
    aws rds create-db-subnet-group --db-subnet-group-name "$DB_ID" \
        --db-subnet-group-description "caffeine RDS subnets (no internet route)" \
        --subnet-ids "$DB_SUBNET_A" "$DB_SUBNET_B" --query DBSubnetGroup.DBSubnetGroupName --output text
fi
if [ "$RDS_EXISTS" = yes ]; then
    echo "  인스턴스 있음 (설정·비밀번호는 바꾸지 않는다)"
else
    WORK=$(mktemp -d)
    trap 'rm -rf "$WORK"' EXIT
    printf '%s' "$DB_PASSWORD" > "$WORK/password" # 명령줄 인자로 넘기면 프로세스 목록에 보인다
    # 만드는 데 5~10분 걸린다. 기다리는 동안 EC2 를 만들고 끝에서 완료를 기다린다
    # --engine-lifecycle-support: 표준 지원이 끝난 버전이면 연장 지원 요금에 가입되는 대신 생성이 실패하게 한다
    aws rds create-db-instance --db-instance-identifier "$DB_ID" --db-instance-class "$DB_INSTANCE_CLASS" \
        --engine mysql --engine-version "$DB_ENGINE_VERSION" \
        --engine-lifecycle-support open-source-rds-extended-support-disabled \
        --allocated-storage 20 --storage-type gp3 --storage-encrypted \
        --db-name "$DB_NAME" --master-username "$DB_USER" --master-user-password "file://$WORK/password" \
        --db-subnet-group-name "$DB_ID" --vpc-security-group-ids "$RDS_SG" --availability-zone "$PUBLIC_AZ" \
        --no-publicly-accessible --no-multi-az --backup-retention-period 1 \
        --query 'DBInstance.DBInstanceStatus' --output text
    echo "  만드는 중 (초기 DB $DB_NAME · 사용자 $DB_USER)"
fi

log "키 페어 $KEY_NAME"
KEY_FILE="$HOME/.ssh/$KEY_NAME.pem"
if aws ec2 describe-key-pairs --key-names "$KEY_NAME" >/dev/null 2>&1; then
    echo "  있음 (개인 키 파일은 처음 만들 때만 받을 수 있다: $KEY_FILE)"
else
    if [ -e "$KEY_FILE" ]; then
        echo "$KEY_FILE 이 이미 있어 덮어쓰지 않는다. 파일을 옮기거나 KEY_NAME 을 바꿔 다시 실행한다." >&2
        exit 1
    fi
    mkdir -p "$HOME/.ssh"
    if ! (umask 077 && aws ec2 create-key-pair --key-name "$KEY_NAME" --key-type ed25519 \
        --query KeyMaterial --output text > "$KEY_FILE"); then
        rm -f "$KEY_FILE"
        exit 1
    fi
    echo "  만듦. 개인 키 $KEY_FILE (다시 받을 수 없으니 지우지 않는다)"
fi

log "고정 IP 와 네트워크 인터페이스 (EC2 가 첫 부팅부터 같은 주소를 쓰게 먼저 만든다)"
ENI_ID=$(aws ec2 describe-network-interfaces --filters "Name=vpc-id,Values=$VPC_ID" "Name=tag:Name,Values=caffeine-app" \
    --query 'NetworkInterfaces[0].NetworkInterfaceId' --output text)
if [ "$ENI_ID" = None ]; then
    ENI_ID=$(aws ec2 create-network-interface --subnet-id "$PUBLIC_SUBNET" --groups "$EC2_SG" \
        --description "caffeine app EC2" --tag-specifications "$(name_tag network-interface caffeine-app)" \
        --query NetworkInterface.NetworkInterfaceId --output text)
    echo "  인터페이스 만듦 $ENI_ID"
else
    echo "  인터페이스 있음 $ENI_ID"
fi
ALLOCATION_ID=$(aws ec2 describe-addresses --filters "Name=tag:Name,Values=caffeine-app" \
    --query 'Addresses[0].AllocationId' --output text)
if [ "$ALLOCATION_ID" = None ]; then
    ALLOCATION_ID=$(aws ec2 allocate-address --domain vpc --tag-specifications "$(name_tag elastic-ip caffeine-app)" \
        --query AllocationId --output text)
    echo "  고정 IP 만듦 $ALLOCATION_ID"
else
    echo "  고정 IP 있음 $ALLOCATION_ID"
fi
ASSOCIATION=$(aws ec2 describe-addresses --allocation-ids "$ALLOCATION_ID" --query 'Addresses[0].AssociationId' --output text)
if [ "$ASSOCIATION" = None ]; then
    aws ec2 associate-address --allocation-id "$ALLOCATION_ID" --network-interface-id "$ENI_ID" >/dev/null
    echo "  고정 IP 를 인터페이스에 연결"
fi
PUBLIC_IP=$(aws ec2 describe-addresses --allocation-ids "$ALLOCATION_ID" --query 'Addresses[0].PublicIp' --output text)

log "EC2 caffeine-app ($INSTANCE_TYPE)"
INSTANCE_ID=$(aws ec2 describe-instances \
    --filters "Name=tag:Name,Values=caffeine-app" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query 'Reservations[0].Instances[0].InstanceId' --output text)
if [ "$INSTANCE_ID" = None ]; then
    AMI_ID=$(aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
        --query Parameter.Value --output text)
    ROOT_DEVICE=$(aws ec2 describe-images --image-ids "$AMI_ID" --query 'Images[0].RootDeviceName' --output text)
    # 디스크 20GB: 이미지 네 개 + 배포 tar + Prometheus 2GB + 로그. 홉 제한 2: 컨테이너가 인스턴스 역할을 읽게(경보 2단계)
    INSTANCE_ID=$(aws ec2 run-instances --image-id "$AMI_ID" --instance-type "$INSTANCE_TYPE" --key-name "$KEY_NAME" \
        --network-interfaces "NetworkInterfaceId=$ENI_ID,DeviceIndex=0" \
        --user-data "file://$BASE_DIR/user-data.sh" \
        --block-device-mappings "DeviceName=$ROOT_DEVICE,Ebs={VolumeSize=20,VolumeType=gp3,Encrypted=true,DeleteOnTermination=true}" \
        --metadata-options "HttpTokens=required,HttpPutResponseHopLimit=2,HttpEndpoint=enabled" \
        --tag-specifications "$(name_tag instance caffeine-app)" "$(name_tag volume caffeine-app)" \
        --query 'Instances[0].InstanceId' --output text)
    echo "  만듦 $INSTANCE_ID (AMI $AMI_ID). 첫 부팅에서 Docker 를 설치한다 — 2~3분"
else
    echo "  있음 $INSTANCE_ID (설정은 바꾸지 않는다)"
fi
aws ec2 wait instance-running --instance-ids "$INSTANCE_ID"

log "RDS 가 준비될 때까지 기다린다 (처음이면 5~10분)"
aws rds wait db-instance-available --db-instance-identifier "$DB_ID"
DB_ENDPOINT=$(aws rds describe-db-instances --db-instance-identifier "$DB_ID" \
    --query 'DBInstances[0].Endpoint.Address' --output text)

log "완료"
echo "EC2   $INSTANCE_ID · $PUBLIC_IP"
echo "RDS   $DB_ID · $DB_ENDPOINT"
echo
echo "다음 순서 (docs/ops/기반-AWS-설정.md)"
echo "  1) Docker 설치 확인 — 버전이 나오면 끝난 것이다. 안 나오면 1~2분 뒤 다시"
echo "       ssh -i $KEY_FILE ec2-user@$PUBLIC_IP 'docker compose version'"
echo "  2) EC2 에서 나가는 길 닫기"
echo "       AWS_REGION=$AWS_REGION bash infra/aws/egress.sh close"
echo "  3) 스키마 적용 (PC 에서 SSH 터널로)"
echo "       ssh -i $KEY_FILE -N -L 13306:$DB_ENDPOINT:3306 ec2-user@$PUBLIC_IP &"
echo "       mysql -h 127.0.0.1 -P 13306 -u $DB_USER -p $DB_NAME < docs/db/db-schema.sql && kill %1"
echo "  4) EC2 의 .env 에 넣을 값 (DB_PASSWORD·JWT_SECRET·GRAFANA_ADMIN_PASSWORD 는 .env.example 참고)"
echo "       DB_URL=jdbc:mysql://$DB_ENDPOINT:3306/$DB_NAME?serverTimezone=UTC&characterEncoding=UTF-8&useUnicode=true&allowPublicKeyRetrieval=true&useSSL=false"
echo "       DB_USERNAME=$DB_USER"
echo "  5) 앱 배포: docs/ops/운영-가이드.md. 경보: docs/ops/경보-AWS-설정.md 에 넣을 값"
echo "       EC2_INSTANCE_ID=$INSTANCE_ID RDS_INSTANCE_ID=$DB_ID"
