# 기반 AWS 설정 — VPC·EC2·RDS를 처음부터 만들기

> 대상: 앱이 올라갈 EC2와 DB(RDS), 그 둘을 담는 네트워크. AWS 계정에 아무것도 없는 상태에서 시작한다.
> 순서: **이 문서** → 앱 배포(`운영-가이드.md`) → 경보(`경보-AWS-설정.md`).
> 마지막 갱신: 2026-11-09

스크립트(`infra/aws/`)는 **PC에서** 관리자 권한 자격 증명으로 실행한다. **더하기만 하고 지우지 않으며, 다시 실행해도 된다**
(이름이 같은 것은 건너뛴다). 지우는 것은 아래 "되돌리기"의 명령으로 직접 한다.

## 구조

```text
iOS 앱 ──(8080)──▶ ┌─ VPC caffeine ──────────────────────────────────────────┐
내 PC ──(SSH 22)─▶ │  caffeine-public 서브넷        caffeine-db-a · db-b 서브넷 │
                   │  ┌ EC2 caffeine-app ┐  3306   ┌ RDS caffeine-db ┐        │
                   │  │ docker compose   │ ──────▶ │ MySQL (비공개)    │        │
                   │  └──────────────────┘         └──────────────────┘        │
                   └──────────────────────────────────────────────────────────┘
EC2 ──✗──▶ 인터넷   (보안 그룹에 나가는 규칙이 없다)
```

| 결정 | 이유 |
|---|---|
| EC2는 **공인 IP가 있는 서브넷**에 둔다 | 앱과 PC가 바깥에서 들어와야 한다. 들어오는 길(인터넷 게이트웨이)은 필요하다 |
| 나가는 길은 **보안 그룹으로** 막는다 | 보안 그룹은 들어온 연결의 응답은 자동으로 내보내고, EC2가 먼저 거는 연결만 규칙을 본다. 나가는 규칙을 RDS(3306)만 남기면 SSH·API는 되고 인터넷으로는 못 나간다. NAT·배스천이 필요 없어 가장 싸고 단순하다 |
| 나가는 길을 막는 이유 | 서버가 침해돼도 데이터를 밖으로 보내거나 원격 조종을 받는 경로가 없다(`../alerting-plan.md` §1.1) |
| RDS는 **비공개** | 인터넷 경로가 없는 서브넷에 두고, EC2 보안 그룹에서 오는 3306만 받는다. PC에서는 EC2를 거치는 SSH 터널로만 접속한다 |
| **고정 IP** | EC2를 껐다 켜도 앱이 접속하는 주소가 바뀌지 않는다 |
| Docker는 **첫 부팅 때** 설치 | 그때만 나가는 길이 열려 있다. 설치를 확인한 뒤 닫는다(`egress.sh close`) |
| MySQL **8.4** | RDS의 MySQL 8.0은 2026-07-31에 표준 지원이 끝나, 새로 만들면 연장 지원 요금(vCPU 시간당 과금)이 붙는다. 8.4는 2029-07-31까지 표준 지원이다. 스크립트는 연장 지원 대상 버전이면 생성이 실패하게 해 두었다 |

## 준비

**PC**: AWS CLI(v2 권장, v1도 동작), bash, ssh, MySQL 클라이언트(스키마 적용용. 없으면 아래 Docker 방법).
`aws sts get-caller-identity`가 쓰려는 계정을 가리키는지 확인한다. 레포 루트에서 실행한다.

| 값 | 무엇 | 예 |
|---|---|---|
| `AWS_REGION` | 리전 | `ap-northeast-2` |
| `SSH_CIDR` | SSH를 허용할 내 PC 주소. `curl -s https://checkip.amazonaws.com` 결과에 `/32` | `203.0.113.7/32` |
| `DB_PASSWORD` | RDS 비밀번호. 영문·숫자 16~41자. **나중에 EC2의 `.env`에 같은 값을 넣는다** | `openssl rand -hex 16` |

선택: `API_CIDR`(기본 `0.0.0.0/0`), `KEY_NAME`(기본 `caffeine-key`), `INSTANCE_TYPE`(기본 `t3.small`), `DB_INSTANCE_CLASS`(기본 `db.t4g.micro`),
`DB_ENGINE_VERSION`(기본 `8.4`). EC2 유형은 **x86_64만** 쓴다 — CI가 amd64 이미지만 만든다(`t4g` 등 arm 유형은 앱이 뜨지 않는다).
`t3.micro`(1GB)는 앱·Prometheus·Grafana·Alertmanager를 함께 띄우기에 메모리가 빠듯하다.

## 생기는 것

| 이름 | 무엇 |
|---|---|
| VPC `caffeine` | `10.20.0.0/16`, DNS 지원·호스트 이름 켬(RDS 주소 해석과 경보 2단계 엔드포인트의 전제) |
| 인터넷 게이트웨이 `caffeine` | 들어오는 SSH·API용 |
| 서브넷 `caffeine-public` | `10.20.1.0/24`. EC2. 라우팅 테이블 `caffeine-public`이 인터넷 게이트웨이로 보낸다 |
| 서브넷 `caffeine-db-a`·`caffeine-db-b` | `10.20.11.0/24`·`10.20.12.0/24`, 가용영역 두 곳(RDS 서브넷 그룹의 조건). 인터넷 경로 없음 |
| 보안 그룹 `caffeine-ec2` | 인바운드 22(`SSH_CIDR`)·8080(`API_CIDR`). 아웃바운드 3306 → `caffeine-rds` (+ `close` 전까지 전체 허용) |
| 보안 그룹 `caffeine-rds` | 인바운드 3306 ← `caffeine-ec2`만 |
| 키 페어 `caffeine-key` | 개인 키는 `~/.ssh/caffeine-key.pem`. **다시 받을 수 없다** |
| 네트워크 인터페이스·고정 IP `caffeine-app` | EC2가 첫 부팅부터 같은 공인 주소를 쓴다 |
| EC2 `caffeine-app` | Amazon Linux 2023 x86_64, 디스크 20GB(gp3, 암호화), IMDSv2·홉 제한 2, 첫 부팅 때 Docker·compose 설치(`user-data.sh`) |
| RDS 서브넷 그룹·인스턴스 `caffeine-db` | MySQL 8.4, 20GB(gp3, 암호화), 비공개, 단일 영역(EC2와 같은 영역), 백업 1일, 초기 DB `caffeine_tracker`, 사용자 `caffeine` |

**비용.** 켜 두는 동안 계속 나가는 것은 EC2, RDS, 공인 IPv4 주소, 디스크다. 기본 유형으로 합쳐 월 40달러 안팎이다(추정 — 요금표로 확인).
쓰지 않을 때 줄이는 방법은 아래 "쓰지 않을 때".

## 실행

```bash
# 1. PC: 만들기 (10분 남짓. RDS 가 가장 오래 걸린다)
read -rs DB_PASSWORD && export DB_PASSWORD     # 입력이 화면·셸 기록에 남지 않는다. 값은 따로 적어 둔다
AWS_REGION=ap-northeast-2 SSH_CIDR=$(curl -s https://checkip.amazonaws.com)/32 bash infra/aws/base.sh
```

끝나면 EC2의 공인 IP·인스턴스 ID, RDS 주소와 다음에 칠 명령을 값이 채워진 채로 출력한다. 아래는 같은 순서다.

```bash
# 2. PC: Docker 설치 확인 — 버전이 나오면 끝난 것이다. 안 나오면 1~2분 뒤 다시
ssh -i ~/.ssh/caffeine-key.pem ec2-user@<공인 IP> 'docker compose version'

# 3. PC: EC2 에서 나가는 길 닫기
AWS_REGION=ap-northeast-2 bash infra/aws/egress.sh close

# 4. PC: 스키마 적용 — RDS 는 비공개라 EC2 를 거치는 SSH 터널로 접속한다
ssh -i ~/.ssh/caffeine-key.pem -N -L 13306:<RDS 주소>:3306 ec2-user@<공인 IP> &
mysql -h 127.0.0.1 -P 13306 -u caffeine -p caffeine_tracker < docs/db/db-schema.sql
kill %1   # 터널 종료
```

MySQL 클라이언트가 없으면 4번의 `mysql` 줄 대신 Docker로 실행한다(Docker Desktop — macOS·Windows. 1번과 같은 셸이어야 `DB_PASSWORD`가 넘어간다).

```bash
docker run --rm -i -e MYSQL_PWD="$DB_PASSWORD" mysql:8.4 mysql -h host.docker.internal -P 13306 -u caffeine caffeine_tracker < docs/db/db-schema.sql
```

**5. EC2의 `.env`.** `~/.env`를 만들어 `.env.example`의 항목을 채운다. `DB_URL`·`DB_USERNAME`은 `base.sh`가 출력한 줄을 그대로 쓰고,
`DB_PASSWORD`는 1번에서 넣은 값, `JWT_SECRET`·`GRAFANA_ADMIN_PASSWORD`는 `.env.example`의 생성 명령으로 만든다.

**6. 앱 배포.** `운영-가이드.md` "수동 배포 절차"와 "Prometheus·Grafana 설치". `<ec2-user>@<ec2-host>`는 `ec2-user@<공인 IP>`이고
`ssh`·`scp`에 `-i ~/.ssh/caffeine-key.pem`을 붙인다.

**7. 경보.** `경보-AWS-설정.md`. 넣을 값은 `EC2_INSTANCE_ID=<base.sh 출력>`, `RDS_INSTANCE_ID=caffeine-db`.
2단계 스크립트가 `caffeine-ec2`에 SNS 엔드포인트로 나가는 443 규칙을 더한다 — 그래도 인터넷으로는 나가지 못한다.

## 확인과 문제 해결

```bash
# PC: 나가는 규칙 — 대상 주소에 0.0.0.0/0 이 없어야 한다
AWS_REGION=ap-northeast-2 bash infra/aws/egress.sh status

# EC2: 실제로 막혔는지 — 5초 뒤 시간 초과가 나야 한다
curl -m 5 https://example.com

# EC2: DB 까지 가는지 — 포트가 열려 있으면 바로 끝난다 (MySQL 클라이언트 없이 확인)
timeout 5 bash -c '</dev/tcp/<RDS 주소>/3306' && echo ok
```

| 증상 | 원인 | 확인·조치 |
|---|---|---|
| `ssh`가 시간 초과 | 내 PC 주소가 바뀌었다(집·학교·테더링) | 새 주소로 `base.sh`를 다시 실행하면 22 규칙이 하나 더해진다. 옛 규칙은 콘솔 EC2 → 보안 그룹 `caffeine-ec2`에서 지운다 |
| `docker: command not found` | 첫 부팅 설치가 아직 안 끝났거나 실패 | EC2: `sudo tail -30 /var/log/cloud-init-output.log`. 실패했으면 `egress.sh open` 뒤 `sudo bash /var/lib/cloud/instance/user-data.txt`, 끝나면 `close` |
| `docker` 가 `permission denied` | 그룹 반영 전 세션 | SSH를 끊고 다시 접속한다 |
| RDS 생성이 엔진 버전 오류로 실패 | `DB_ENGINE_VERSION`이 연장 지원 대상(8.0 등)이다 | 기본값 8.4를 쓴다 |
| 앱 기동 시 `Unknown database` | 초기 DB 없이 만들어진 RDS | 이 스크립트로 만들면 생기지 않는다. `운영-가이드.md` "알려진 함정" |
| 앱 기동 시 `Schema-validation` | 4번(스키마 적용)을 건너뜀 | 4번 실행 후 앱 재기동 |

## 운영 중에

**OS 패치·Docker 갱신.** EC2가 직접 받아야 하므로 잠깐 연다. 앱 이미지는 이 방법을 쓰지 않는다(`docker load`).

```bash
AWS_REGION=ap-northeast-2 bash infra/aws/egress.sh open     # 443 만 연다
ssh -i ~/.ssh/caffeine-key.pem ec2-user@<공인 IP> 'sudo dnf upgrade -y'
AWS_REGION=ap-northeast-2 bash infra/aws/egress.sh close
```

**쓰지 않을 때.** 지우지 않고 멈추면 데이터와 설정이 남는다. 디스크·고정 IP 요금은 멈춘 동안에도 나간다.
RDS는 멈춘 지 7일이 지나면 AWS가 자동으로 다시 켠다.

```bash
aws ec2 stop-instances --instance-ids <i-...>            # 다시: start-instances. 컨테이너는 restart 정책으로 다시 뜬다
aws rds stop-db-instance --db-instance-identifier caffeine-db   # 다시: start-db-instance
```

## 되돌리기

**DB 데이터가 지워진다.** 남기려면 `--skip-final-snapshot --delete-automated-backups` 대신 `--final-db-snapshot-identifier caffeine-db-final`을 쓴다(스냅숏 보관 요금).
경보 자원을 만들었으면 `경보-AWS-설정.md`의 "되돌리기"를 **먼저** 한다 — 엔드포인트와 그 보안 그룹이 남아 있으면 VPC가 지워지지 않는다.

```bash
export AWS_DEFAULT_REGION=ap-northeast-2
VPC_ID=$(aws ec2 describe-vpcs --filters Name=tag:Name,Values=caffeine --query 'Vpcs[0].VpcId' --output text)
sg() { aws ec2 describe-security-groups --filters "Name=vpc-id,Values=$VPC_ID" "Name=group-name,Values=$1" --query 'SecurityGroups[0].GroupId' --output text; }

# EC2 → 고정 IP → 네트워크 인터페이스
INSTANCE_ID=$(aws ec2 describe-instances --filters Name=tag:Name,Values=caffeine-app Name=instance-state-name,Values=pending,running,stopping,stopped \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)
aws ec2 terminate-instances --instance-ids "$INSTANCE_ID"
aws ec2 wait instance-terminated --instance-ids "$INSTANCE_ID"
ALLOCATION_ID=$(aws ec2 describe-addresses --filters Name=tag:Name,Values=caffeine-app --query 'Addresses[0].AllocationId' --output text)
ASSOCIATION_ID=$(aws ec2 describe-addresses --allocation-ids "$ALLOCATION_ID" --query 'Addresses[0].AssociationId' --output text)
aws ec2 disassociate-address --association-id "$ASSOCIATION_ID"
aws ec2 release-address --allocation-id "$ALLOCATION_ID"
ENI_ID=$(aws ec2 describe-network-interfaces --filters Name=tag:Name,Values=caffeine-app --query 'NetworkInterfaces[0].NetworkInterfaceId' --output text)
aws ec2 delete-network-interface --network-interface-id "$ENI_ID"

# RDS (5~10분) → 서브넷 그룹
aws rds delete-db-instance --db-instance-identifier caffeine-db --skip-final-snapshot --delete-automated-backups
aws rds wait db-instance-deleted --db-instance-identifier caffeine-db
aws rds delete-db-subnet-group --db-subnet-group-name caffeine-db

# 보안 그룹 — 서로 가리키는 규칙을 먼저 지워야 그룹이 지워진다
aws ec2 revoke-security-group-egress --group-id "$(sg caffeine-ec2)" \
  --ip-permissions "IpProtocol=tcp,FromPort=3306,ToPort=3306,UserIdGroupPairs=[{GroupId=$(sg caffeine-rds)}]"
aws ec2 delete-security-group --group-id "$(sg caffeine-rds)"
aws ec2 delete-security-group --group-id "$(sg caffeine-ec2)"

# 라우팅 테이블 연결 해제 → 서브넷 → 라우팅 테이블 → 인터넷 게이트웨이 → VPC
RTB_ID=$(aws ec2 describe-route-tables --filters "Name=vpc-id,Values=$VPC_ID" Name=tag:Name,Values=caffeine-public --query 'RouteTables[0].RouteTableId' --output text)
for association in $(aws ec2 describe-route-tables --route-table-ids "$RTB_ID" --query 'RouteTables[0].Associations[].RouteTableAssociationId' --output text); do
  aws ec2 disassociate-route-table --association-id "$association"
done
for name in caffeine-public caffeine-db-a caffeine-db-b; do
  aws ec2 delete-subnet --subnet-id "$(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$VPC_ID" "Name=tag:Name,Values=$name" --query 'Subnets[0].SubnetId' --output text)"
done
aws ec2 delete-route-table --route-table-id "$RTB_ID"
IGW_ID=$(aws ec2 describe-internet-gateways --filters Name=tag:Name,Values=caffeine --query 'InternetGateways[0].InternetGatewayId' --output text)
aws ec2 detach-internet-gateway --internet-gateway-id "$IGW_ID" --vpc-id "$VPC_ID"
aws ec2 delete-internet-gateway --internet-gateway-id "$IGW_ID"
aws ec2 delete-vpc --vpc-id "$VPC_ID"

# 키 페어 (다시 만들 거면 남겨도 된다 — 요금 없음)
aws ec2 delete-key-pair --key-name caffeine-key && rm ~/.ssh/caffeine-key.pem
```

## 검증 기록 (2026-11-09)

실제 계정에서는 아직 실행하지 않았다. 확인한 것:

- `base.sh`·`egress.sh`를 moto 5.2.5 서버와 AWS CLI v1에 대고 돌렸다. `base.sh`는 두 번 모두 종료 코드 0이고 두 번째는 전부 "있음"으로 건너뛰었다.
  만들어진 EC2(유형·IMDSv2·홉 제한·키·user-data)와 RDS(버전·비공개·단일 영역·gp3 20GB·암호화·초기 DB·사용자) 설정이 위 표와 같다.
  `egress.sh`는 `close` → `close` → `open` → `close`가 모두 기대대로 규칙을 바꿨다. 입력값 검사(SSH 전체 개방, 비밀번호 길이·문자)도 확인했다.
- 그 위에서 `monitoring/aws/1-`·`2-` 스크립트가 종료 코드 0으로 돌았다(2단계의 VPC DNS 검사 통과).
- "되돌리기" 명령을 같은 moto 서버에서 끝까지 실행해 VPC까지 지워지는 것을 확인했다.
- 앱을 MySQL 8.4.13 컨테이너에 붙여 스키마 검증(`ddl-auto: validate`) 통과, 가입·로그인·기록 저장(한글·이모지)·조회·학습 실행을 확인했다.
  레포의 E2E(`./gradlew e2eTest`)와 로컬 compose는 아직 `mysql:8.0` 이미지를 쓴다.
- **moto로 확인되지 않는 것**: `user-data.sh`의 실제 설치(패키지·내려받기·체크섬은 주소와 값만 확인), 보안 그룹이 실제로 트래픽을 막는지,
  RDS 생성 대기 시간, 가용영역별 유형 제공 여부, `--engine-lifecycle-support`의 거부 동작. moto는 미리 만든 네트워크 인터페이스로 띄운
  인스턴스의 보안 그룹을 비워 돌려주어, 경보 2단계가 `caffeine-ec2`에 규칙을 더하는 부분은 이 조합으로 확인하지 못했다.
