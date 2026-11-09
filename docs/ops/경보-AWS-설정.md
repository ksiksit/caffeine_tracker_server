# 경보 AWS 설정 — 토픽·알람과 EC2 → SNS 경로

> 대상: `../alerting-plan.md` §11의 **1·2단계**. 레포 쪽 준비는 끝나 있고(경보 규칙·Alertmanager 설정), 이 문서는 AWS 쪽 작업이다.
> 3단계(Alertmanager 켜기)는 `운영-가이드.md` "경보" 절, 4단계(Lambda → Discord, 하트비트 알람)는 이후 작업이다.
> 마지막 갱신: 2026-11-09

스크립트(`monitoring/aws/`)는 **PC에서** 관리자 권한 자격 증명으로 실행한다. EC2에서 실행하지 않는다.
EC2는 인터넷이 없고 이 권한도 없다. 두 스크립트 모두 **더하기만 하고 지우지 않으며, 다시 실행해도 된다.**
스크립트 대신 콘솔로 할 수도 있다(아래 "콘솔로 할 때").

## 준비

**PC**: AWS CLI(v2 권장, v1도 동작)와 bash. `aws sts get-caller-identity`가 운영 계정을 가리키는지 확인한다. 레포 루트에서 실행한다.

**모을 값**

| 값 | 어디서 | 예 |
|---|---|---|
| 리전 | EC2 콘솔 오른쪽 위 | `ap-northeast-2` |
| 운영 EC2 인스턴스 ID | EC2 → 인스턴스 | `i-0123456789abcdef0` |
| 운영 RDS 식별자 | RDS → 데이터베이스 (없으면 RDS 알람을 건너뛴다) | `caffeine-db` |
| 경보 받을 이메일 | | |

**필요한 권한** (설계 미결 1). 학교·조직 계정이면 일부가 막혀 있을 수 있다. 막히면 스크립트가 `AccessDenied`·`UnauthorizedOperation`으로 멈춘다.
멈춘 단계를 설계 §10 1번에 따라 판단한다.

| 서비스 | 동작 |
|---|---|
| STS | `GetCallerIdentity` |
| SNS | `CreateTopic`, `SetTopicAttributes`, `Subscribe`, `ListSubscriptionsByTopic` |
| CloudWatch | `PutMetricAlarm`, `SetAlarmState`(확인용) |
| EC2 | `Describe*`, `CreateSecurityGroup`, `AuthorizeSecurityGroupIngress`·`Egress`, `CreateVpcEndpoint`, `ModifyInstanceMetadataOptions`, `AssociateIamInstanceProfile` |
| IAM | `GetRole`, `CreateRole`, `GetInstanceProfile`, `CreateInstanceProfile`, `AddRoleToInstanceProfile`, `PutRolePolicy`, `PassRole` |

## 생기는 것

| 단계 | 이름 | 무엇 |
|---|---|---|
| 1 | SNS 토픽 `caffeine-alerts` | 경보 → 이메일(+ 이후 Lambda). 접근 정책: 계정 관리 + 같은 계정 CloudWatch 알람의 발행 |
| 1 | SNS 토픽 `caffeine-heartbeat` | 하트비트 전용. 구독은 4단계에서 Lambda만 |
| 1 | 이메일 구독 | `caffeine-alerts` → 입력한 이메일 |
| 1 | CloudWatch 알람 `caffeine-ec2-status-check` | EC2 상태 검사 실패 2분 |
| 1 | `caffeine-ec2-cpu-credit` | t 계열 인스턴스만. CPU 크레딧 20 미만 15분 |
| 1 | `caffeine-rds-free-storage`, `caffeine-rds-cpu` | RDS 식별자를 준 경우. 여유 저장공간 2GiB 미만 15분, CPU 80% 초과 15분 |
| 1 | `caffeine-ec2-auto-recover` | `EC2_AUTO_RECOVER=yes`일 때만. AWS 쪽 하드웨어 문제면 인스턴스 자동 복구 |
| 2 | 보안 그룹 `caffeine-sns-endpoint` | EC2 보안 그룹에서 오는 443만 받는다 |
| 2 | EC2 보안 그룹 아웃바운드 규칙 | 위 보안 그룹으로 나가는 443. **인터넷으로 나가는 길은 열지 않는다** |
| 2 | SNS 인터페이스 엔드포인트 | EC2 서브넷, 프라이빗 DNS, 정책은 두 토픽 발행만 (`monitoring/aws/policies/sns-endpoint.json`) |
| 2 | EC2 역할의 인라인 정책 `caffeine-sns-publish` | 두 토픽 `sns:Publish`만. 역할이 없으면 `caffeine-ec2` 역할을 만들어 붙인다 |
| 2 | 인스턴스 메타데이터 홉 제한 2 | 컨테이너가 인스턴스 역할 자격 증명을 받으려면 필요하다(설계 §7.2). 재부팅 없이 적용된다 |

알람은 울릴 때와 풀릴 때 모두 `caffeine-alerts`로 알린다. 비용은 엔드포인트가 대부분이다(설계 §10 비용 추정, AZ 하나에 월 약 7~10달러).

## 1단계 — 토픽·이메일·인프라 알람

```bash
AWS_REGION=ap-northeast-2 ALERT_EMAIL=<이메일> EC2_INSTANCE_ID=<i-...> RDS_INSTANCE_ID=<RDS 식별자> \
  bash monitoring/aws/1-topics-and-alarms.sh
```

마지막에 `.env`에 넣을 값 3개(`ALERT_TOPIC_ARN`, `HEARTBEAT_TOPIC_ARN`, `AWS_REGION`)를 출력한다. 3단계에서 쓴다.

**확인**

1. 메일함의 "AWS Notification - Subscription Confirmation" 메일에서 **Confirm subscription**을 누른다. 안 보이면 스팸함을 본다.
   스크립트를 다시 돌리면 확인 메일을 다시 보낸다.
2. 알람을 강제로 울려 메일이 오는지 본다. 1분 안에 실제 상태로 돌아오므로 `ALARM` 메일 뒤에 `OK` 메일이 이어서 온다.

```bash
aws cloudwatch set-alarm-state --region ap-northeast-2 --alarm-name caffeine-ec2-status-check --state-value ALARM --state-reason "경로 확인"
```

여기까지 되면 **코드 없이 EC2 전체 장애(인스턴스 멈춤)를 메일로 받는다.**

## 2단계 — EC2 → SNS 경로

```bash
AWS_REGION=ap-northeast-2 EC2_INSTANCE_ID=<i-...> bash monitoring/aws/2-ec2-to-sns.sh
```

VPC의 DNS 설정(`enableDnsSupport`·`enableDnsHostnames`)이 꺼져 있으면 스크립트는 **바꾸지 않고 멈춘다.**
프라이빗 DNS 엔드포인트의 전제인데, 같은 VPC의 다른 서비스에도 영향이 있어 직접 판단한다. 켜는 명령은 스크립트가 출력한다.
같은 VPC에 SNS 엔드포인트가 이미 있으면 그것을 쓰되, 다른 서비스도 쓰고 있을 수 있어 정책·보안 그룹을 바꾸지 않는다.
이 경우 두 가지를 직접 확인하라고 출력한다.

**확인** — 엔드포인트가 `available`이 된 뒤(몇 분) EC2에서 한다. EC2는 인터넷이 없으므로 확인용 AWS CLI 이미지를 PC에서 옮긴다.

```bash
# PC: 확인용 이미지 저장·전송 (2-ec2-to-sns.sh 의 태그와 같게)
docker pull --platform linux/amd64 amazon/aws-cli:2.22.0
docker save -o aws-cli-image.tar amazon/aws-cli:2.22.0
scp aws-cli-image.tar <ec2-user>@<ec2-host>:~/
```

```bash
# EC2: 1) SNS 주소가 사설 IP(10.x · 172.16~31.x · 192.168.x)로 풀리는가
getent hosts sns.ap-northeast-2.amazonaws.com

# EC2: 2) 컨테이너에서 인스턴스 역할로 발행 — 메일이 오면 Alertmanager 가 쓸 경로 전체가 된다
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

## 콘솔로 할 때

스크립트와 같은 결과를 콘솔에서 만든다. 정책 JSON은 `monitoring/aws/policies/`에서 `__REGION__`·`__ACCOUNT_ID__`를 바꿔 붙여 넣는다.

1. **SNS → 주제 생성**: 표준, 이름 `caffeine-alerts`, 암호화 끔, 액세스 정책에 `sns-topic-alerts.json`. 같은 방법으로 `caffeine-heartbeat`(기본 정책).
2. **SNS → 구독 생성**: `caffeine-alerts`, 프로토콜 이메일 → 메일에서 승인.
3. **CloudWatch → 경보 생성**: "생기는 것" 표의 알람마다 지표·통계·기간·임계값을 `1-topics-and-alarms.sh`에 적힌 값으로. 경보 상태와 OK 상태 모두 `caffeine-alerts`로 알림.
4. **VPC → 보안 그룹 생성**: `caffeine-sns-endpoint`, 인바운드 HTTPS(443), 소스는 EC2 보안 그룹. EC2 보안 그룹의 아웃바운드에 HTTPS(443), 대상 `caffeine-sns-endpoint`.
5. **VPC → 엔드포인트 생성**: AWS 서비스 `com.amazonaws.<리전>.sns`, EC2의 VPC·서브넷, 보안 그룹 `caffeine-sns-endpoint`, DNS 이름 활성화, 정책은 사용자 지정 `sns-endpoint.json`.
6. **IAM**: EC2에 역할이 있으면 그 역할에 인라인 정책 `caffeine-sns-publish`(`ec2-sns-publish.json`). 없으면 EC2용 역할을 만들어 같은 정책을 넣고, EC2 → 작업 → 보안 → IAM 역할 수정으로 붙인다.
7. **EC2 → 작업 → 인스턴스 설정 → 인스턴스 메타데이터 옵션 수정**: 메타데이터 응답 홉 제한 2.

## 되돌리기

스크립트가 만든 것만 지운다. 엔드포인트만 지워도 비용 대부분이 멈춘다. 나중에 2단계 스크립트를 다시 돌리면 다시 생긴다.
2단계에서 "이미 있음"으로 나온 엔드포인트는 원래 있던 것이므로 지우지 않는다.

```bash
export AWS_DEFAULT_REGION=ap-northeast-2
INSTANCE_ID=<i-...>
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
VPC_ID=$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" --query 'Reservations[0].Instances[0].VpcId' --output text)

# 2단계: 엔드포인트 → 보안 그룹 규칙 → 보안 그룹 (보안 그룹은 엔드포인트가 완전히 지워진 뒤에 지워진다)
ENDPOINT_ID=$(aws ec2 describe-vpc-endpoints --filters "Name=vpc-id,Values=$VPC_ID" "Name=service-name,Values=com.amazonaws.$AWS_DEFAULT_REGION.sns" \
  --query 'VpcEndpoints[0].VpcEndpointId' --output text)
aws ec2 delete-vpc-endpoints --vpc-endpoint-ids "$ENDPOINT_ID"
ENDPOINT_SG=$(aws ec2 describe-security-groups --filters "Name=vpc-id,Values=$VPC_ID" "Name=group-name,Values=caffeine-sns-endpoint" \
  --query 'SecurityGroups[0].GroupId' --output text)
for sg in $(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" --query 'Reservations[0].Instances[0].SecurityGroups[].GroupId' --output text); do
  aws ec2 revoke-security-group-egress --group-id "$sg" --ip-permissions "IpProtocol=tcp,FromPort=443,ToPort=443,UserIdGroupPairs=[{GroupId=$ENDPOINT_SG}]"
done
aws ec2 delete-security-group --group-id "$ENDPOINT_SG"   # DependencyViolation 이면 엔드포인트 정리 중이다. 1~2분 뒤 이 줄만 다시

# 2단계: 역할의 인라인 정책
PROFILE_NAME=$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" --query 'Reservations[0].Instances[0].IamInstanceProfile.Arn' --output text | sed 's#.*/##')
ROLE_NAME=$(aws iam get-instance-profile --instance-profile-name "$PROFILE_NAME" --query 'InstanceProfile.Roles[0].RoleName' --output text)
aws iam delete-role-policy --role-name "$ROLE_NAME" --policy-name caffeine-sns-publish
# 스크립트가 caffeine-ec2 역할을 새로 만들었던 경우에만 (ROLE_NAME 이 caffeine-ec2):
ASSOCIATION_ID=$(aws ec2 describe-iam-instance-profile-associations --filters "Name=instance-id,Values=$INSTANCE_ID" \
  --query 'IamInstanceProfileAssociations[0].AssociationId' --output text)
aws ec2 disassociate-iam-instance-profile --association-id "$ASSOCIATION_ID"
aws iam remove-role-from-instance-profile --instance-profile-name caffeine-ec2 --role-name caffeine-ec2
aws iam delete-instance-profile --instance-profile-name caffeine-ec2
aws iam delete-role --role-name caffeine-ec2

# 1단계: 알람, 토픽 (토픽을 지우면 구독도 함께 지워진다)
aws cloudwatch delete-alarms --alarm-names caffeine-ec2-status-check caffeine-ec2-auto-recover caffeine-ec2-cpu-credit \
  caffeine-rds-free-storage caffeine-rds-cpu
aws sns delete-topic --topic-arn "arn:aws:sns:$AWS_DEFAULT_REGION:$ACCOUNT_ID:caffeine-alerts"
aws sns delete-topic --topic-arn "arn:aws:sns:$AWS_DEFAULT_REGION:$ACCOUNT_ID:caffeine-heartbeat"
```

홉 제한은 2로 두어도 해가 없다. 되돌리려면 `aws ec2 modify-instance-metadata-options --instance-id "$INSTANCE_ID" --http-put-response-hop-limit 1`.
