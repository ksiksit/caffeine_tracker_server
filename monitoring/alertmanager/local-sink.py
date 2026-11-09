"""로컬 경보 수신기 (docker-compose.monitoring.yml 의 alert-sink 전용).

Alertmanager 웹훅을 받아 한 경보당 한 줄씩 로그에 찍는다. 운영에서는 이 자리에 SNS 가 있다.
웹훅 본문은 템플릿을 거치지 않은 JSON 이라 실제 이메일·Slack 문장과는 다르다 — 문장은 monitoring/check.sh 가 렌더링해 보여 준다.
표준 라이브러리만 쓴다.
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        try:
            payload = json.loads(body)
            for alert in payload.get("alerts", []):
                labels = alert.get("labels", {})
                print(
                    f"[{self.path.strip('/')}] {alert.get('status', '?'):8} "
                    f"{labels.get('severity', '-'):9} {labels.get('alertname', '-'):20} "
                    f"{alert.get('annotations', {}).get('summary', '')}",
                    flush=True,
                )
        except ValueError:
            print(f"[{self.path.strip('/')}] JSON 이 아닌 본문 {len(body)}바이트", flush=True)
        self.send_response(200)
        self.end_headers()

    def log_message(self, *args):
        pass  # 요청마다 찍히는 접근 로그는 끈다


if __name__ == "__main__":
    print("alert-sink: 0.0.0.0:8080 에서 대기", flush=True)
    HTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
