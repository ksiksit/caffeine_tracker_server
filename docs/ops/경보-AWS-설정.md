# 경보 AWS 설정 — 토픽·알람, EC2 → SNS 경로, Slack 전달

> 대상: `../alerting-plan.md` §11의 **1·2·4단계**. 자원은 기반과 같은 Terraform(`infra/terraform/`, 경보 부분은 `modules/alerting/`)이 한 번에 만든다.
> 이 문서는 Terraform이 못 하는 준비(Slack 웹훅)와, 만든 뒤 **단계별로 확인하는 순서**다. Terraform 사용법은 `기반-AWS-설정.md`.
> 순서: 준비 → apply(`기반-AWS-설정.md` "실행") → 1단계 확인 → 2단계 확인 → 3단계(Alertmanager 켜기, `운영-가이드.md` "경보" 절) → 4단계 확인.
> 마지막 갱신: 2026-11-09

`terraform.tfvars`의 `alerting_enabled`(기본 `true`)가 이 문서의 자원 전체를 켜고 끈다. `alert_email`에 경보를 받을 이메일을 넣는다.
콘솔에서 같은 이름으로 직접 만들지 않는다 — Terraform이 만들려다 이름이 겹쳐 실패한다.

## 준비 — Slack 웹훅 (Slack으로도 받을 때)

이메일만 받을 거면 건너뛴다. 나중에 넣어도 된다 — 넣기 전에는 Lambda가 Slack 전달에 실패해 `caffeine-alert-relay-errors` 메일이 올 수 있다.
하트비트는 웹훅과 무관하게 동작한다.

**Slack 수신 웹훅 만들기.** 경보를 받을 **채널**을 먼저 만들어 둔다(예: `#caffeine-alerts`).

1. 브라우저에서 `https://api.slack.com/apps` → **Create New App** → **From scratch** 아래의 **Pick features**
   (2026-11 화면 기준. 예전의 "From scratch" 버튼 자리다) → 앱 이름(예: `caffeine-alerts`)과 워크스페이스를 고른다.
   이미 만든 앱이 **Your Apps** 목록에 있으면 새로 만들지 말고 그것을 연다.
2. 왼쪽 **Incoming Webhooks** → **Activate Incoming Webhooks**를 켠다.
3. 아래 **Add New Webhook** → 목록에서 **`#`로 시작하는 채널**을 고르고 **허용**. 워크스페이스 설정에 따라 관리자 승인이 필요할 수 있다.
4. 표의 **Channel** 칸이 그 채널 이름인 줄의 **Webhook URL**(`https://hooks.slack.com/services/T…/B…/…`)을 복사한다.

경보는 그 채널에만 올라온다. 채널이 아니라 앱 자신(앱과의 1:1 대화창)을 목적지로 고르면 Slack이 `messages_tab_disabled`(500)로 거부한다.
URL을 아는 사람은 누구나 그 채널에 글을 쓸 수 있으므로 채팅·레포·셸 기록에 남기지 않는다. 새어 나갔으면 같은 화면에서 지우고 새로 만든다.

**URL을 SSM에 넣기.** 콘솔: Systems Manager → 파라미터 스토어 → 파라미터 생성 → 이름 `/caffeine/alert-relay/slack-webhook`,
유형 **SecureString**, 값에 URL. CLI(macOS)는 복사한 URL을 클립보드에서 바로 읽는다 — 터미널에 붙여 넣지 않아 기록에 남지 않는다.

```bash
# 1) Slack 화면에서 URL 을 복사한 뒤, 이 줄은 손으로 입력한다 (이 줄을 복사하면 클립보드의 URL 이 사라진다)
URL=$(pbpaste | tr -d '[:space:]')
# 2) 값은 보이지 않게 형식만 확인
[[ $URL == https://hooks.slack.com/services/* ]] && echo "형식 맞음" || echo "형식 틀림 (길이 ${#URL})"
# 3) 저장 (바꿀 때는 --overwrite 를 붙인다). "Version" 이 나와야 저장된 것이다. AWS CLI v2 기준 — v1 은 https:// 값을 주소로 보고 내려받는다
aws ssm put-parameter --region ap-northeast-2 --name /caffeine/alert-relay/slack-webhook --type SecureString --value "$URL"; unset URL
# 4) Lambda 를 거치지 않고 웹훅만 확인 — "ok" 와 200 이 나오고 채널에 글이 올라와야 한다. 거부되면 사유 이름이 나온다
curl -s -w '\n상태: %{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{"text":"웹훅 확인"}' \
  "$(aws ssm get-parameter --region ap-northeast-2 --name /caffeine/alert-relay/slack-webhook --with-decryption --query Parameter.Value --output text)"
```

웹훅은 Terraform이 다루지 않는다. state에 남기지 않으려는 것이고, 그래서 `destroy`해도 파라미터는 남는다.

## 생기는 것

| 단계 | 이름 | 무엇 |
|---|---|---|
| 1 | SNS 토픽 `caffeine-alerts` | 경보 → 이메일 + Lambda. 접근 정책: 계정 관리 + 같은 계정 CloudWatch 알람의 발행 |
| 1 | SNS 토픽 `caffeine-heartbeat` | 하트비트 전용. 구독은 Lambda만 |
| 1 | 이메일 구독 | `caffeine-alerts` → `alert_email` |
| 1 | CloudWatch 알람 `caffeine-ec2-status-check` | EC2 상태 검사 실패 2분 |
| 1 | `caffeine-ec2-cpu-credit` | t 계열 인스턴스만. CPU 크레딧 20 미만 15분 |
| 1 | `caffeine-rds-free-storage`, `caffeine-rds-cpu` | 여유 저장공간 2GiB 미만 15분, CPU 80% 초과 15분 |
| 1 | `caffeine-ec2-auto-recover` | `ec2_auto_recover = true`일 때만. AWS 쪽 하드웨어 문제면 인스턴스 자동 복구 |
| 2 | 보안 그룹 `caffeine-sns-endpoint` | EC2 보안 그룹에서 오는 443만 받는다 |
| 2 | EC2 보안 그룹 아웃바운드 규칙 | 위 보안 그룹으로 나가는 443. **인터넷으로 나가는 길은 열지 않는다** |
| 2 | SNS 인터페이스 엔드포인트 | EC2 서브넷, 프라이빗 DNS, 정책은 두 토픽 발행만 |
| 2 | EC2 역할의 인라인 정책 `caffeine-sns-publish` | 두 토픽 `sns:Publish`만. 역할 `caffeine-ec2`와 홉 제한 2는 기반이 만든다 |
| 4 | IAM 역할 `caffeine-alert-relay` | 자기 로그 쓰기, `Caffeine/Monitoring` 네임스페이스 메트릭, 웹훅 파라미터 읽기만 |
| 4 | 로그 그룹 `/aws/lambda/caffeine-alert-relay` | 보관 14일 |
| 4 | Lambda `caffeine-alert-relay` | `monitoring/lambda/alert-relay`, nodejs22.x, arm64, 128MB, 10초, VPC 없음 |
| 4 | 구독 두 개 + 호출 권한 | `caffeine-alerts`·`caffeine-heartbeat` → Lambda |
| 4 | 알람 `caffeine-alert-relay-errors` | Lambda 오류 1건 이상. Slack 전달이 깨지면 이메일로 알린다 |
| 4 | 알람 `caffeine-heartbeat-missing` | 하트비트 15분 없음(데이터 없음도 위반). `heartbeat_alarm_enabled = true`일 때만 |

직접 만드는 것: SSM 파라미터 `/caffeine/alert-relay/slack-webhook`(SecureString).
알람은 울릴 때와 풀릴 때 모두 `caffeine-alerts`로 알린다. 비용은 엔드포인트가 대부분이다(설계 §10 비용 추정, AZ 하나에 월 약 7~10달러).
설정값의 원본은 `infra/terraform/modules/alerting/*.tf`다.

## 1단계 확인 — 이메일과 인프라 알람

1. 메일함의 "AWS Notification - Subscription Confirmation" 메일에서 **Confirm subscription**을 누른다. 안 보이면 스팸함을 본다.
   승인 여부: `aws sns list-subscriptions --query "Subscriptions[?Protocol=='email'].SubscriptionArn" --output text` — `PendingConfirmation`이면 아직이다.
2. 알람을 강제로 울려 메일이 오는지 본다. 1분 안에 실제 상태로 돌아오므로 `ALARM` 메일 뒤에 `OK` 메일이 이어서 온다.

```bash
aws cloudwatch set-alarm-state --region ap-northeast-2 --alarm-name caffeine-ec2-status-check --state-value ALARM --state-reason "경로 확인"
```

여기까지 되면 **EC2 전체 장애(인스턴스 멈춤)를 메일로 받는다.**

## 2단계 확인 — EC2 → SNS 경로

가장 놓치기 쉬운 네트워크·권한 경로라 Alertmanager를 켜기 전에 손으로 확인한다. EC2는 인터넷이 없으므로 확인용 AWS CLI 이미지를 PC에서 옮긴다.

```bash
# PC: 확인용 이미지 저장·전송
docker pull --platform linux/amd64 amazon/aws-cli:2.22.0
docker save --platform linux/amd64 -o aws-cli-image.tar amazon/aws-cli:2.22.0
scp -i ~/.ssh/caffeine-key aws-cli-image.tar ec2-user@<고정 IP>:~/
```

```bash
# EC2: 1) SNS 주소가 사설 IP(10.20.x)로 풀리는가
getent hosts sns.ap-northeast-2.amazonaws.com

# EC2: 2) 컨테이너에서 인스턴스 역할로 발행 — 메일이 오면 Alertmanager 가 쓸 경로 전체가 된다
#         토픽 ARN 은 PC 의 terraform -chdir=infra/terraform output -raw env_file 에 있다
docker load -i aws-cli-image.tar
docker run --rm amazon/aws-cli:2.22.0 sns publish --region ap-northeast-2 \
  --topic-arn arn:aws:sns:ap-northeast-2:<계정 ID>:caffeine-alerts \
  --subject "[TEST] ec2-to-sns" --message "EC2 컨테이너 → SNS 엔드포인트 경로 확인"
```

| 증상 | 원인 | 볼 곳 |
|---|---|---|
| `getent`가 공인 IP를 돌려준다 | 엔드포인트가 아직 `available`이 아니거나 프라이빗 DNS가 꺼져 있다 | 엔드포인트 상태·프라이빗 DNS, VPC DNS 설정 |
| 발행이 멈춰 있다가 시간 초과 | 네트워크가 막혔다 | 엔드포인트 보안 그룹 인바운드, EC2 보안 그룹 아웃바운드, 서브넷 네트워크 ACL |
| `Unable to locate credentials` | 컨테이너가 인스턴스 역할을 못 읽는다 | 홉 제한(2인가), 인스턴스 프로필 연결(`aws ec2 describe-iam-instance-profile-associations`) |
| `AuthorizationError ... SNS:Publish` | 권한이 없다 | 역할 인라인 정책 `caffeine-sns-publish`, 엔드포인트 정책 |

메일이 오면 3단계로 간다: `운영-가이드.md` "경보" 절의 **Alertmanager 켜기**.

## 4단계 확인 — Slack 전달과 하트비트

```bash
# PC: 시험 메시지 — 이메일과 Slack 둘 다 와야 한다
aws sns publish --region ap-northeast-2 --topic-arn arn:aws:sns:ap-northeast-2:<계정 ID>:caffeine-alerts \
  --subject "[TEST] alert-relay" --message "Slack 경로 확인"

# PC: Alertmanager 를 켠 지 몇 분 뒤 — Heartbeat 메트릭이 보이면 하트비트가 오고 있다
aws cloudwatch list-metrics --region ap-northeast-2 --namespace Caffeine/Monitoring --metric-name Heartbeat
```

Heartbeat 메트릭이 보이면 `terraform.tfvars`에 **`heartbeat_alarm_enabled = true`**를 넣고 apply한다. 그때 `caffeine-heartbeat-missing` 알람이 만들어진다.
처음부터 만들지 않는 이유는, 하트비트가 오기 전에 만들면 15분 뒤 바로 울리기 때문이다. Alertmanager를 오래 내려 둘 때는 다시 `false`로 돌린다.

```bash
terraform -chdir=infra/terraform apply    # "1 to add" 가 하트비트 알람이다
```

| 증상 | 원인 | 볼 곳 |
|---|---|---|
| 이메일은 오는데 Slack 은 안 온다 | Lambda 오류 | `aws logs tail /aws/lambda/caffeine-alert-relay --since 10m`(CLI v2). 오류 줄의 `Slack 응답 <번호> <사유>`에서 사유가 Slack이 준 이름이다. 파라미터가 있는지: `aws ssm describe-parameters --parameter-filters "Key=Name,Values=/caffeine/alert-relay/slack-webhook"` |
| 로그에 `Slack 수신 웹훅 URL 형식이 아니다` | 파라미터 값이 `https://hooks.slack.com/services/` 로 시작하지 않는다(다른 주소·앱 토큰 등) | SSM 파라미터 값. 보안상 다른 주소로는 보내지 않는다 |
| 로그에 `Slack 응답 500 messages_tab_disabled` | 웹훅의 목적지가 채널이 아니라 앱과의 대화창이다 | 웹훅을 채널로 새로 만들어 파라미터 값을 바꾼다(`--overwrite`) |
| 로그에 `Slack 응답 404` / `403` | 웹훅이 지워졌거나(`no_service`) 앱이 채널에서 빠졌다 | Slack 앱 설정의 Incoming Webhooks 에서 새로 만들어 파라미터 값을 바꾼다. 재배포는 필요 없다 |
| 로그에 `Slack 응답 410` | 채널이 보관(archive)됐다 | 채널을 되살리거나 다른 채널로 웹훅을 새로 만든다 |
| 로그에 `Slack 응답 429` | 웹훅 속도 제한(대략 초당 1건) | 일시적이다. SNS 가 다시 호출한다 |
| `AccessDeniedException ... ssm:GetParameter` | 역할 정책·파라미터 이름 | 파라미터 이름이 정확히 `/caffeine/alert-relay/slack-webhook` 인가 |
| 하트비트 알람이 울렸다 | 경보 경로 어딘가가 끊겼다 | `운영-가이드.md` "경보" 절의 하트비트 점검 순서 |

Lambda 는 웹훅 URL을 5분 동안 캐시한다. 파라미터 값을 바꾸면 늦어도 5분 뒤에는 새 URL로 보낸다.

## Lambda 코드를 고쳤을 때

`monitoring/lambda/alert-relay`의 `index.mjs`·`relay.mjs`를 고치고 `sh monitoring/check.sh`(단위 테스트)를 통과시킨 뒤 apply한다.
Terraform이 두 파일을 zip으로 묶어 내용이 바뀌었을 때만 새로 올린다.

```bash
terraform -chdir=infra/terraform apply    # aws_lambda_function.relay 가 "updated in-place" 로 나온다
```

## 끄기·되돌리기

경보 자원만 지울 때는 `terraform.tfvars`에 `alerting_enabled = false`를 넣고 apply한다. 기반(EC2·RDS)은 그대로다. 다시 `true`로 apply하면 다시 생긴다 —
이메일 구독은 새로 승인해야 하고, EC2의 Alertmanager는 그동안 SNS 발행에 실패하므로 내려 둔다
(`docker compose -f docker-compose.prod.yml --profile alerting stop alertmanager`).

전부 지우는 것은 `기반-AWS-설정.md` "되돌리기". 웹훅 파라미터와 Slack 쪽 웹훅(Slack 앱 설정)은 따로 지운다.

검증 기록은 `기반-AWS-설정.md` 끝과 `../alerting-plan.md` §9.
