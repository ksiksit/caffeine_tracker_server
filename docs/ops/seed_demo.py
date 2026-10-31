#!/usr/bin/env python3
"""시연 계정에 지난 6밤의 카페인·수면 기록을 넣고 학습을 실행한다. 다시 실행해도 수면은 중복되지 않는다(카페인은 --skip-caffeine).

    python3 docs/ops/seed_demo.py --base-url http://localhost:8080 --email demo@example.com --password '...'
"""
import argparse
import json
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

# 서버 SleepStage 의 HealthKit 값
HK_IN_BED, HK_CORE, HK_DEEP, HK_REM = 0, 3, 4, 5
# 잠들기까지 걸린 분. 밤마다 달라야 대시보드 산점도가 의미 있다.
SOL_MINUTES = [28, 34, 22, 41, 30, 25]
DOSE_MG = 200


def call(base_url, method, path, body=None, token=None, query=None):
    url = base_url + path + ("?" + urllib.parse.urlencode(query) if query else "")
    request = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request) as response:
        return json.load(response)["data"]


def iso(value):
    return value.isoformat(timespec="seconds")


def seed_night(base_url, token, wake_date, zone, sol_minutes, skip_caffeine):
    bed = datetime.combine(wake_date - timedelta(days=1), time(23, 0), zone)
    asleep = bed + timedelta(minutes=sol_minutes)
    wake = bed + timedelta(hours=8)
    deep_end = asleep + timedelta(hours=2)
    rem_end = deep_end + timedelta(hours=1)
    if not skip_caffeine:
        call(base_url, "POST", "/api/caffeine-records", token=token, body={
            "amount": DOSE_MG, "drinkName": "시연 아메리카노", "timestamp": iso(bed - timedelta(hours=7))})
    stages = [(HK_IN_BED, bed, wake), (HK_DEEP, asleep, deep_end), (HK_REM, deep_end, rem_end), (HK_CORE, rem_end, wake)]
    samples = [{"clientUuid": f"demo-{wake_date}-{value}", "start": iso(start), "end": iso(end), "hkValue": value}
               for value, start, end in stages]
    call(base_url, "POST", "/api/sleep/samples", token=token, body={"samples": samples})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--email", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--nickname", default="시연")
    parser.add_argument("--tz", default="Asia/Seoul")
    parser.add_argument("--skip-caffeine", action="store_true", help="재실행 시 카페인 기록 중복 방지")
    args = parser.parse_args()

    credentials = {"email": args.email, "password": args.password}
    try:
        call(args.base_url, "POST", "/api/auth/signup", body={**credentials, "nickname": args.nickname})
    except urllib.error.HTTPError as error:
        if error.code != 409:  # 이미 있는 계정이면 그대로 쓴다
            raise
    token = call(args.base_url, "POST", "/api/auth/login", body=credentials)["accessToken"]

    zone = ZoneInfo(args.tz)
    today = datetime.now(zone).date()
    for days_ago, sol_minutes in zip(range(len(SOL_MINUTES), 0, -1), SOL_MINUTES):
        seed_night(args.base_url, token, today - timedelta(days=days_ago), zone, sol_minutes, args.skip_caffeine)

    run = call(args.base_url, "POST", "/api/learning/run", token=token, query={"tz": args.tz})
    dashboard = call(args.base_url, "GET", "/api/learning/dashboard", token=token)
    print(f"학습 {run['updatedCount']}밤 반영, 사유: {run['skipReason']}, 관측 {dashboard['count']}건")


if __name__ == "__main__":
    main()
