# 기반 AWS 설정 — Terraform으로 VPC·EC2·RDS 만들기

> 대상: 앱이 올라갈 EC2와 DB(RDS), 그 둘을 담는 네트워크. AWS 계정에 아무것도 없는 상태에서 시작한다.
> 같은 Terraform이 경보용 자원(SNS·엔드포인트·Lambda·알람)도 함께 만든다 — 그쪽의 준비·확인은 `경보-AWS-설정.md`.
> 순서: **이 문서** → 앱 배포(`운영-가이드.md`) → 경보 확인(`경보-AWS-설정.md`).
> 마지막 갱신: 2026-11-09

원본은 `infra/terraform/`이다. **PC에서** 관리자 권한 자격 증명으로 실행한다. Terraform이 하는 일은 AWS 자원을 만들고 지우는 것까지다.
스키마 적용, `.env`, 이미지 옮기기, 앱 배포는 그대로 직접 한다(CD가 아니다).

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
| Docker는 **첫 부팅 때** 설치 | 그때만 나가는 길을 연다(`ec2_egress_open=true`). 설치를 확인한 뒤 닫는다 |
| MySQL **8.4** | RDS의 MySQL 8.0은 2026-07-31에 표준 지원이 끝나, 새로 만들면 연장 지원 요금(vCPU 시간당 과금)이 붙는다. 8.4는 2029-07-31까지 표준 지원이다. 연장 지원 대상 버전이면 생성이 실패하게 해 두었다 |
| 비밀은 **SSM 파라미터 스토어**에 직접 | DB 비밀번호와 Slack 웹훅은 Terraform 변수로 받지 않는다. Terraform은 DB 비밀번호를 읽어 RDS로 보내기만 하고(쓰기 전용 인자) state에 남기지 않는다 |
| state는 **PC의 파일** | 운영자가 한 명이다. `infra/terraform/terraform.tfstate`, 레포에 올리지 않는다(아래 "state 파일") |

## 준비

**PC에 필요한 것**: Terraform 1.11 이상(macOS: `brew install hashicorp/tap/terraform`), AWS CLI, ssh, MySQL 클라이언트(스키마 적용용. macOS: `brew install mysql-client` — PATH 에 걸리지 않으므로 `$(brew --prefix mysql-client)/bin/mysql`로 부른다. 없으면 아래 Docker 방법).
`aws sts get-caller-identity`가 쓰려는 계정을 가리키는지 확인한다. 명령은 모두 **레포 루트에서** 실행한다.

```bash
# 1. SSH 키 — 개인 키(~/.ssh/caffeine-key)는 PC 에만 있고, 공개 키(.pub)만 AWS 로 간다
ssh-keygen -t ed25519 -f ~/.ssh/caffeine-key -C caffeine

# 2. DB 비밀번호를 만들어 바로 SSM 에 넣는다 (화면·셸 기록에 값이 남지 않는다. 영문·숫자 16~41자)
aws ssm put-parameter --region ap-northeast-2 --name /caffeine/db-password --type SecureString --value "$(openssl rand -hex 16)"

# 3. 변수 파일 — 내 PC 주소, 공개 키, 경보 받을 이메일을 채운다
cp infra/terraform/terraform.tfvars.example infra/terraform/terraform.tfvars
curl -s https://checkip.amazonaws.com    # ssh_cidr 에 이 주소 + /32
cat ~/.ssh/caffeine-key.pub               # ssh_public_key 에 이 한 줄
```

| 변수 | 무엇 | 기본 |
|---|---|---|
| `ssh_cidr` | SSH를 허용할 내 PC 주소(`/32`). 전체(`/0`)는 거부한다 | 필수 |
| `ssh_public_key` | 공개 키 한 줄 | 필수 |
| `alert_email` | 경보를 받을 이메일 | `alerting_enabled`면 필수 |
| `region` | 리전 | `ap-northeast-2` |
| `instance_type` | EC2 유형. **x86_64만** — CI가 amd64 이미지만 만든다(`t4g` 등 arm은 거부한다). `t3.micro`(1GB)는 앱·Prometheus·Grafana·Alertmanager를 함께 띄우기에 빠듯하다 | `t3.small` |
| `db_instance_class` · `db_engine_version` | RDS 유형·MySQL 버전 | `db.t4g.micro` · `8.4` |
| `api_cidr` | API(8080)를 허용할 주소 | `0.0.0.0/0` |
| `ec2_egress_open` | EC2에서 인터넷으로 나가는 443. **명령줄에서만** 잠깐 켠다 | `false` |
| `alerting_enabled` | 경보용 자원을 만든다 | `true` |
| `heartbeat_alarm_enabled` · `ec2_auto_recover` | `경보-AWS-설정.md` | `false` |
| `db_password_version` · `db_final_snapshot` | 아래 "운영 중에"·"되돌리기" | `1` · `false` |

경보를 Slack으로도 받으려면 apply 전에 웹훅을 SSM에 넣어 둔다(`경보-AWS-설정.md` "준비"). 나중에 넣어도 된다 — 그동안은 이메일만 온다.

## 생기는 것

| 이름 | 무엇 |
|---|---|
| VPC `caffeine` | `10.20.0.0/16`, DNS 지원·호스트 이름 켬(RDS 주소 해석과 경보 엔드포인트의 전제) |
| 인터넷 게이트웨이 `caffeine` | 들어오는 SSH·API용 |
| 서브넷 `caffeine-public` | `10.20.1.0/24`. EC2. 라우팅 테이블 `caffeine-public`이 인터넷 게이트웨이로 보낸다 |
| 서브넷 `caffeine-db-a`·`caffeine-db-b` | `10.20.11.0/24`·`10.20.12.0/24`, 가용영역 두 곳(RDS 서브넷 그룹의 조건). 인터넷 경로 없음 |
| 보안 그룹 `caffeine-ec2` | 인바운드 22(`ssh_cidr`)·8080(`api_cidr`). 아웃바운드 3306 → `caffeine-rds` (+ 경보를 켜면 443 → SNS 엔드포인트) |
| 보안 그룹 `caffeine-rds` | 인바운드 3306 ← `caffeine-ec2`만 |
| 키 페어 `caffeine-key` | 공개 키만 등록한다 |
| IAM 역할·인스턴스 프로필 `caffeine-ec2` | EC2 인스턴스 역할. 권한은 경보 모듈이 붙이는 SNS 발행뿐이다 |
| 네트워크 인터페이스·고정 IP `caffeine-app` | EC2가 첫 부팅부터 같은 공인 주소를 쓴다 |
| EC2 `caffeine-app` | Amazon Linux 2023 x86_64, 디스크 20GB(gp3, 암호화), IMDSv2·홉 제한 2, 첫 부팅 때 Docker·compose 설치(`user-data.sh`) |
| RDS 서브넷 그룹·인스턴스 `caffeine-db` | MySQL 8.4, 20GB(gp3, 암호화), 비공개, 단일 영역(EC2와 같은 영역), 백업 1일, 초기 DB `caffeine_tracker`, 사용자 `caffeine` |
| 경보용 자원 | `alerting_enabled`일 때. 목록은 `경보-AWS-설정.md` "생기는 것" |

Terraform 밖에서 직접 만드는 것: SSM 파라미터 `/caffeine/db-password`, `/caffeine/alert-relay/slack-webhook`. `destroy`해도 남는다.

**비용.** 켜 두는 동안 계속 나가는 것은 EC2, RDS, 공인 IPv4 주소, 디스크, 경보의 SNS 엔드포인트다. 기본값으로 합쳐 월 50달러 안팎이다(추정 — 요금표로 확인).
쓰지 않을 때 줄이는 방법은 아래 "쓰지 않을 때".

## 실행

```bash
# 1. PC: 처음 한 번 — 공급자 내려받기
terraform -chdir=infra/terraform init

# 2. PC: 만들기 (10분 남짓. RDS 가 가장 오래 걸린다). 만들 것의 목록을 보여 주고 yes 를 물으면 입력한다.
#    첫 부팅의 Docker 설치를 위해 이번만 나가는 길을 연 채로 만든다
terraform -chdir=infra/terraform apply -var ec2_egress_open=true

# 3. PC: Docker 설치 확인 — 버전이 나오면 끝난 것이다. 안 나오면 1~2분 뒤 다시
$(terraform -chdir=infra/terraform output -raw ssh_command) 'docker compose version && docker ps'

# 4. PC: 나가는 길 닫기 — 변수 없이 다시 apply 하면 기본값(닫힘)으로 돌아간다. "1 to destroy" 가 그 규칙이다
terraform -chdir=infra/terraform apply

# 5. PC: 스키마 적용 — RDS 는 비공개라 EC2 를 거치는 SSH 터널로 접속한다 (PC 의 13306 → RDS 3306)
#    -f 는 인증 뒤 터널을 뒤로 보낸다(호스트 앞에 둔다). macOS 는 mysql 대신 $(brew --prefix mysql-client)/bin/mysql
ssh -i ~/.ssh/caffeine-key -f -N -L 13306:$(terraform -chdir=infra/terraform output -raw db_endpoint):3306 \
  ec2-user@$(terraform -chdir=infra/terraform output -raw ec2_public_ip)
export MYSQL_PWD=$(aws ssm get-parameter --region ap-northeast-2 --name /caffeine/db-password --with-decryption --query Parameter.Value --output text)
mysql -h 127.0.0.1 -P 13306 -u caffeine caffeine_tracker < docs/db/db-schema.sql
mysql -h 127.0.0.1 -P 13306 -u caffeine caffeine_tracker -e "SELECT VERSION(); SHOW TABLES;"   # 8.4.x 와 테이블 5개
pkill -f "13306:"; unset MYSQL_PWD   # 터널 종료, 비밀번호 변수 삭제
```

MySQL 클라이언트가 없으면 5번의 `mysql` 줄 대신 Docker로 실행한다(Docker Desktop — macOS·Windows).

```bash
docker run --rm -i -e MYSQL_PWD="$(aws ssm get-parameter --region ap-northeast-2 --name /caffeine/db-password --with-decryption --query Parameter.Value --output text)" \
  mysql:8.4 mysql -h host.docker.internal -P 13306 -u caffeine caffeine_tracker < docs/db/db-schema.sql
```

**6. EC2의 `.env`.** PC에서 값을 모아 만든 뒤 보내고 PC의 사본은 지운다. 비밀 값이 화면에 나오지 않는다.
`env_file` 출력이 `DB_URL`·`DB_USERNAME`(경보를 켰으면 `ALERT_TOPIC_ARN`·`HEARTBEAT_TOPIC_ARN`·`AWS_REGION`도)을 준다.
**서버를 새로 만들 때만 한다** — 다시 실행하면 `JWT_SECRET`이 바뀌어 발급된 토큰이 모두 무효가 되고, Grafana 비밀번호는 첫 기동 때의 값으로 남는다.

```bash
EC2=ec2-user@$(terraform -chdir=infra/terraform output -raw ec2_public_ip)
( umask 077; {
  terraform -chdir=infra/terraform output -raw env_file; echo
  echo "DB_PASSWORD=$(aws ssm get-parameter --region ap-northeast-2 --name /caffeine/db-password --with-decryption --query Parameter.Value --output text)"
  echo "JWT_SECRET=$(openssl rand -base64 48)"
  echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 24)"
} > /tmp/caffeine.env )
scp -i ~/.ssh/caffeine-key /tmp/caffeine.env $EC2:.env && rm /tmp/caffeine.env
ssh -i ~/.ssh/caffeine-key $EC2 'chmod 600 .env; cut -d= -f1 .env'   # 항목 이름만 확인
```

Grafana 비밀번호는 필요할 때 EC2에서 `grep GRAFANA ~/.env`로 본다.

**7. 앱 배포.** `운영-가이드.md` "수동 배포 절차"와 "Prometheus·Grafana 설치". `<ec2-user>@<ec2-host>`는 `ec2-user@<고정 IP>`이고
`ssh`·`scp`에 `-i ~/.ssh/caffeine-key`를 붙인다. 고정 IP: `terraform -chdir=infra/terraform output -raw ec2_public_ip`.

**8. 경보 확인.** `경보-AWS-설정.md`.

apply가 중간에 실패하면 원인을 고치고 같은 명령을 다시 실행한다. 이미 만든 것은 건너뛴다.

## 확인과 문제 해결

```bash
# PC: 나가는 길 — "닫힘" 이어야 한다
terraform -chdir=infra/terraform output ec2_egress

# EC2: 실제로 막혔는지 — 5초 뒤 시간 초과가 나야 한다
curl -m 5 https://example.com

# EC2: DB 까지 가는지 — 포트가 열려 있으면 바로 끝난다 (MySQL 클라이언트 없이 확인)
timeout 5 bash -c '</dev/tcp/<RDS 주소>/3306' && echo ok

# PC: 코드와 실제가 같은지 — "No changes" 여야 한다
terraform -chdir=infra/terraform plan
```

| 증상 | 원인 | 확인·조치 |
|---|---|---|
| `couldn't find resource` (`/caffeine/db-password`) | SSM 파라미터가 없거나 리전이 다르다 | "준비" 2번. plan·apply·destroy 모두 이 파라미터를 읽는다 |
| `SSM /caffeine/db-password 값은 영문·숫자 16~41자` | 직접 넣은 값에 특수문자가 있거나 짧다 | `--overwrite`로 다시 넣는다 |
| `ssh`가 시간 초과 | 내 PC 주소가 바뀌었다(집·학교·테더링·통신사 재배정). 허용하지 않은 주소의 요청은 오류 없이 버려져 시간 초과로 보인다 | `curl -s https://checkip.amazonaws.com`과 `terraform.tfvars`의 `ssh_cidr`을 비교한다. 다르면 새 주소로 고치고 apply — 규칙이 교체된다("1 to change"). Docker 설치 확인 전이면 `-var ec2_egress_open=true`를 붙여 나가는 길을 유지한다 |
| 주소는 같은데 `ssh`가 시간 초과 | 지금 네트워크가 22번을 막거나(회사·학교), 서버까지 길이 없다 | `nc -vz -G 5 <고정 IP> 8080` — `Connection refused`면 서버까지는 닿는다(앱을 올리기 전 기준). 시간 초과면 `aws ec2 describe-instance-status`로 인스턴스 상태를 본다 |
| `docker: command not found` | 첫 부팅 설치가 아직 안 끝났거나 실패 | EC2: `sudo tail -30 /var/log/cloud-init-output.log`. 실패했으면 `apply -var ec2_egress_open=true` 뒤 `sudo bash /var/lib/cloud/instance/user-data.txt`, 끝나면 `apply` |
| `docker`가 `permission denied` | 그룹 반영 전 세션 | SSH를 끊고 다시 접속한다 |
| RDS 생성이 엔진 버전 오류로 실패 | `db_engine_version`이 연장 지원 대상(8.0 등)이다 | 기본값 8.4를 쓴다 |
| 앱 기동 시 `Schema-validation` | 5번(스키마 적용)을 건너뜀 | 5번 실행 후 앱 재기동 |
| plan이 만든 적 없는 변경을 보여 준다 | 콘솔에서 손으로 바꿨다 | 코드가 원본이다. 콘솔 변경을 유지하려면 코드에 옮기고, 아니면 apply로 되돌린다 |

## state 파일

`infra/terraform/terraform.tfstate`가 "무엇을 만들었는지"의 기록이다. Terraform은 이 파일로 실제 자원을 찾는다.

- **지우지 않는다.** 없어지면 Terraform이 자원을 못 찾아, 다시 apply하면 같은 이름으로 또 만들려다 실패한다. 그때는 콘솔에서 직접 지워야 한다.
- 레포에 올라가지 않는다(`.gitignore`). PC를 바꿀 때는 `terraform.tfstate`와 `terraform.tfvars`를 같이 옮긴다.
- 비밀번호·웹훅은 들어 있지 않다. 계정 ID·IP·자원 ID는 들어 있다.
- `.terraform/`(공급자 바이너리, 수백 MB)와 `.build/`(Lambda zip)는 지워도 된다. `init`·`apply`가 다시 만든다.
- `.terraform.lock.hcl`은 공급자 버전 고정 파일이라 레포에 올린다. 공급자를 올릴 때: `terraform -chdir=infra/terraform init -upgrade`.

## 운영 중에

**OS 패치·Docker 갱신.** EC2가 직접 받아야 하므로 잠깐 연다. 앱 이미지는 이 방법을 쓰지 않는다(`docker load`).

```bash
terraform -chdir=infra/terraform apply -var ec2_egress_open=true    # 443 만 연다
$(terraform -chdir=infra/terraform output -raw ssh_command) 'sudo dnf upgrade -y'
terraform -chdir=infra/terraform apply                              # 닫는다
```

**DB 비밀번호 바꾸기.** SSM 값을 `--overwrite`로 바꾸고 → `terraform.tfvars`에 `db_password_version = 2`(바꿀 때마다 1씩) → apply →
EC2의 `.env`의 `DB_PASSWORD`를 고치고 앱 재기동.

**쓰지 않을 때.** 멈추면 데이터와 설정이 남는다. 디스크·고정 IP·엔드포인트 요금은 멈춘 동안에도 나간다.
RDS는 멈춘 지 7일이 지나면 AWS가 자동으로 다시 켠다. 오래 안 쓸 거면 아래 "되돌리기"로 전부 지우고 필요할 때 다시 만든다.

```bash
aws ec2 stop-instances --instance-ids "$(terraform -chdir=infra/terraform output -raw ec2_instance_id)"   # 다시: start-instances
aws rds stop-db-instance --db-instance-identifier caffeine-db                                             # 다시: start-db-instance
```

경보 비용(엔드포인트)만 끄려면 `terraform.tfvars`에 `alerting_enabled = false`를 넣고 apply한다. 경보 자원만 지워진다.

**서버를 새로 만들기.** AMI가 새로 나오거나 `user-data.sh`를 고쳐도 서버는 그대로다(첫 부팅에만 의미가 있어 무시하게 해 두었다).
새 서버가 필요하면 `terraform -chdir=infra/terraform apply -replace=aws_instance.app -var ec2_egress_open=true` — 디스크가 새로 만들어지므로
`.env`·이미지·compose 파일을 다시 올려야 한다. 고정 IP와 RDS는 그대로다.

## 되돌리기

**DB 데이터가 지워진다.** 남기려면 먼저 `terraform.tfvars`에 `db_final_snapshot = true`를 넣고 apply한 뒤 destroy한다
(스냅숏 `caffeine-db-final`이 남고 보관 요금이 든다. 같은 이름의 스냅숏이 이미 있으면 실패하므로 옛것을 먼저 지운다).

```bash
# 지울 것의 목록을 보여 주고 yes 를 물으면 입력한다. 10분 남짓
terraform -chdir=infra/terraform destroy

# Terraform 밖에서 만든 것 — destroy 가 끝난 뒤에 지운다 (destroy 도 DB 비밀번호 파라미터를 읽는다)
aws ssm delete-parameter --region ap-northeast-2 --name /caffeine/db-password
aws ssm delete-parameter --region ap-northeast-2 --name /caffeine/alert-relay/slack-webhook   # 넣었을 때만
```

다시 만들 때는 "준비" 2번(비밀번호)부터 한다. SSH 키와 `terraform.tfvars`는 그대로 쓴다.

## 검증 기록

### 실제 계정 (2026-11-10)

빈 계정에서 이 문서 순서대로 실행했다(macOS arm64, Terraform 1.17.1, AWS CLI 2.39). 확인된 것:

- `plan`이 44개 추가(`-var ec2_egress_open=true` 기준), `apply`가 한 번에 44개를 만들었다. moto에서 실패하던 CloudWatch 알람도 만들어졌다.
- 첫 부팅 설치: SSH 접속 후 `Docker Compose version v2.39.4`, `docker ps` 동작.
- 변수 없이 apply → "1 to destroy". 그 뒤 EC2에서 `curl -m 5 https://example.com`이 시간 초과, SSH는 그대로 된다.
- `ssh_cidr`만 바꿔 apply → "1 to change"(SSH 규칙만).
- SSH 터널로 스키마 적용(RDS MySQL 8.4, 테이블 5개), 앱 기동(`ddl-auto: validate` 통과, 헬스 `UP`), 인터넷에서 8080으로 가입·로그인.
- 경보 1~4단계 확인(`경보-AWS-설정.md`): 테스트 알람 메일, EC2 컨테이너 → SNS 엔드포인트 발행, Alertmanager의 `Watchdog`, 하트비트 메트릭 뒤
  `heartbeat_alarm_enabled = true` apply → "1 to add", Slack 수신.

**아직 실행하지 않은 것**: `destroy`, `alerting_enabled = false`, DB 비밀번호 교체(`db_password_version`), `-replace=aws_instance.app`, MySQL 8.0 거부 동작.

### moto (2026-11-09)

실제 계정에서 실행하기 전에 확인한 것(Terraform 1.17.1, AWS 공급자 6.72.0):

- `terraform fmt -check`·`validate` 통과. `monitoring/check.sh` 7번과 CI가 같은 검사를 돌린다.
- moto 5.2.5 서버에 대고 apply했다. CloudWatch 알람을 뺀 나머지 자원이 모두 만들어졌고, EC2(유형·고정 IP·IMDSv2·홉 제한·키·인스턴스 프로필),
  RDS(8.4·비공개·단일 영역·gp3 20GB·암호화·초기 DB·사용자), Lambda(런타임·arm64·128MB·10초·zip에 두 파일), 구독 3개, 엔드포인트(프라이빗 DNS),
  역할 정책이 "생기는 것" 표와 같다.
- `-var ec2_egress_open=true`로 만든 뒤 변수 없이 apply하면 인터넷 규칙 하나만 지워지고, 나가는 규칙은 RDS·SNS 엔드포인트 둘만 남는다.
- state 파일에 DB 비밀번호가 없다(`password_wo`는 null로 기록된다). 파라미터가 없을 때와 형식이 틀릴 때 plan이 위 표의 메시지로 멈춘다.
- `heartbeat_alarm_enabled=true`는 알람 하나만 더한다. `alerting_enabled=false`는 경보 모듈 자원만 지우고 기반은 건드리지 않는다.
- 입력값 검사: SSH 전체 개방(`/0`), arm 유형(`t4g.small`)을 거부한다.
- destroy로 VPC까지 지워졌다(아래 moto 차이 한 곳은 손으로 넘겼다).
- 앱을 MySQL 8.4.13 컨테이너에 붙여 스키마 검증(`ddl-auto: validate`) 통과, 가입·로그인·기록 저장(한글·이모지)·조회·학습 실행을 확인했다.
  E2E(`docker-compose.e2e.yml`)와 로컬 compose(`docker-compose.yml`)도 운영과 같은 `mysql:8.4` 이미지를 쓴다.

**moto와 실제가 달랐던 곳.**

- CloudWatch 알람: 이 공급자 버전이 쓰는 프로토콜을 moto가 받지 못해 생성이 실패했다. 같은 알람 블록 7개를 옛 프로토콜을 쓰는 공급자(5.60.0)로
  moto에 적용해 지표·통계·주기·횟수·임계값·누락 데이터 처리가 설계 §5와 같은 것은 확인했다.
- destroy: moto는 종료된 인스턴스의 네트워크 인터페이스를 계속 "붙어 있음"으로 돌려줘 인터페이스 삭제가 멈춘다. 실제 AWS는 종료와 함께 떼어 낸다.
- moto는 RDS의 `engine_lifecycle_support`를 돌려주지 않아 plan에 늘 변경 한 건이 보였다. 연장 지원 버전을 거부하는 동작도 확인하지 못했다.
- `user-data.sh`의 실제 설치, 보안 그룹의 실제 차단, 이메일 구독 승인 흐름은 moto로 볼 수 없었다 — 위 "실제 계정"에서 확인했다.
