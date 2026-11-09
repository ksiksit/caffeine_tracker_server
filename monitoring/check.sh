#!/bin/sh
# 모니터링 설정 검사. CI(build-and-push)가 빌드 전에 돌리고, 로컬에서도 같은 명령으로 돌린다:
#   monitoring/check.sh
#
# promtool·amtool 이 PATH 에 있으면 그것을, 없으면 Docker 이미지 안의 것을 쓴다.
# 이미지 태그는 docker-compose.prod.yml 과 같게 유지한다 (운영과 같은 버전으로 검사해야 의미가 있다).
#
# 검사 항목
#   1. Prometheus 설정·경보 규칙 문법 (promtool check config — rule_files 의 alerts.yml 까지 읽는다)
#   2. 경보 규칙 경계값 테스트 (promtool test rules alerts.test.yml)
#   3. Alertmanager 설정 문법 — 운영·로컬 두 파일 (amtool check-config)
#   4. 두 파일의 route·inhibit_rules 가 같은지 (로컬에서 확인한 라우팅이 운영과 같다는 보장)
#   5. 라우팅 결과 — 하트비트는 heartbeat, 나머지는 alerts 수신자로 가는지 (amtool config routes test)
#   6. 메시지 템플릿 렌더링을 기대 결과와 비교, 제목이 ASCII 인지 (SNS 는 제목에 한글이 있으면 발행을 거부한다)
#   7. AWS 설정 파일 — 정책 JSON 문법, 자리표시자가 스크립트가 채우는 것뿐인지, 설정 스크립트 문법 (bash -n)
#   8. Lambda alert-relay 단위 테스트 (node --test, Node 20 이상. 6번의 기대 렌더링 결과를 입력으로 쓴다)
set -eu

PROMETHEUS_IMAGE=prom/prometheus:v3.5.0
ALERTMANAGER_IMAGE=prom/alertmanager:v0.28.1

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

if command -v promtool >/dev/null 2>&1; then
    promtool() { command promtool "$@"; }
else
    promtool() { docker run --rm -v "$ROOT":/w:ro -w /w --entrypoint promtool "$PROMETHEUS_IMAGE" "$@"; }
fi
if command -v amtool >/dev/null 2>&1; then
    amtool() { command amtool "$@"; }
else
    amtool() { docker run --rm -v "$ROOT":/w:ro -w /w --entrypoint amtool "$ALERTMANAGER_IMAGE" "$@"; }
fi

AM=monitoring/alertmanager
step() { printf '\n== %s\n' "$1"; }

step "1. Prometheus 설정·경보 규칙 문법"
promtool check config monitoring/prometheus/prometheus.yml

step "2. 경보 규칙 경계값 테스트"
promtool test rules monitoring/prometheus/alerts.test.yml

step "3. Alertmanager 설정 문법"
amtool check-config "$AM/alertmanager.yml" "$AM/alertmanager.local.yml"

step "4. 운영·로컬 route·inhibit_rules 일치"
routing() { sed -n '/^route:/,/^receivers:/p' "$1"; }
routing "$AM/alertmanager.yml" > /tmp/check-routing-prod.$$
routing "$AM/alertmanager.local.yml" > /tmp/check-routing-local.$$
if ! diff -u /tmp/check-routing-prod.$$ /tmp/check-routing-local.$$; then
    rm -f /tmp/check-routing-*.$$
    echo "FAIL: alertmanager.yml 과 alertmanager.local.yml 의 route·inhibit_rules 가 다르다" >&2
    exit 1
fi
rm -f /tmp/check-routing-*.$$
echo "SUCCESS"

step "5. 라우팅 결과"
for config in "$AM/alertmanager.yml" "$AM/alertmanager.local.yml"; do
    amtool config routes test --config.file="$config" --verify.receivers=heartbeat alertname=Watchdog severity=heartbeat
    amtool config routes test --config.file="$config" --verify.receivers=alerts alertname=AppDown severity=critical
    amtool config routes test --config.file="$config" --verify.receivers=alerts alertname=HeapHigh severity=warning
done

step "6. 메시지 템플릿"
render() { # $1 = testdata 이름
    amtool template render --template.glob="$AM/templates/*.tmpl" --template.data="$AM/testdata/$1.json" \
        --template.text='{{ template "caffeine.subject" . }}
---
{{ template "caffeine.message" . }}'
}
for case in firing resolved; do
    actual=$(render "$case")
    expected=$(cat "$AM/testdata/$case.expected.txt")
    if [ "$actual" != "$expected" ]; then
        echo "FAIL: $case 렌더링이 testdata/$case.expected.txt 와 다르다. 실제 결과:" >&2
        printf '%s\n' "$actual" >&2
        exit 1
    fi
    subject=$(printf '%s\n' "$actual" | head -n 1)
    if printf '%s' "$subject" | LC_ALL=C grep -q '[^ -~]'; then
        echo "FAIL: 제목에 ASCII 가 아닌 문자가 있다 — SNS 가 발행을 거부한다: $subject" >&2
        exit 1
    fi
    echo "$case: $subject"
done

step "7. AWS 설정 파일 (monitoring/aws)"
for policy in monitoring/aws/policies/*.json; do
    if command -v python3 >/dev/null 2>&1; then
        python3 -m json.tool "$policy" >/dev/null || { echo "FAIL: $policy 가 올바른 JSON 이 아니다" >&2; exit 1; }
    fi
    # common.sh 의 render() 가 채우는 것은 __REGION__·__ACCOUNT_ID__ 뿐이다. 다른 자리표시자는 그대로 AWS 에 들어간다
    unknown=$(grep -o '__[A-Z_]*__' "$policy" | grep -v -e '^__REGION__$' -e '^__ACCOUNT_ID__$' || true)
    if [ -n "$unknown" ]; then
        echo "FAIL: $policy 에 채워지지 않는 자리표시자가 있다: $unknown" >&2
        exit 1
    fi
done
command -v python3 >/dev/null 2>&1 || echo "python3 가 없어 정책 JSON 문법 검사는 건너뛰었다"
for script in monitoring/aws/*.sh; do
    bash -n "$script"
done
echo "SUCCESS"

step "8. Lambda alert-relay 단위 테스트"
if command -v node >/dev/null 2>&1; then
    node --test monitoring/lambda/alert-relay/relay.test.mjs > /tmp/check-lambda.$$ 2>&1 || {
        cat /tmp/check-lambda.$$ >&2
        rm -f /tmp/check-lambda.$$
        echo "FAIL: Lambda 단위 테스트 실패" >&2
        exit 1
    }
    grep -E '^# (tests|pass|fail)' /tmp/check-lambda.$$
    rm -f /tmp/check-lambda.$$
else
    echo "node 가 없어 건너뛰었다 (CI 는 돌린다)"
fi

printf '\n모든 검사 통과\n'
