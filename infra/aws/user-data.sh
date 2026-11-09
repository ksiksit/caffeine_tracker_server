#!/bin/bash
# EC2 첫 부팅 때 root 로 한 번 실행된다 (base.sh 가 --user-data 로 넘긴다). Amazon Linux 2023 · x86_64 기준.
# Docker 와 compose 플러그인을 설치한다. 이때만 인터넷이 필요하다 — 끝난 것을 확인한 뒤 egress.sh close 로 나가는 길을 닫는다.
# 로그: EC2 의 /var/log/cloud-init-output.log
set -euxo pipefail

COMPOSE_VERSION=v2.39.4
COMPOSE_SHA256=7af95166a730b87e172d4fc9aefea8725d3c6c7327d59149267b452114ddb7d4 # docker-compose-linux-x86_64

# 부팅 직후에는 네트워크가 아직 안 잡혀 실패할 수 있어 5분까지 다시 시도한다
for attempt in $(seq 1 30); do
    dnf install -y docker && break
    sleep 10
done
command -v docker

plugin=/usr/local/lib/docker/cli-plugins/docker-compose
mkdir -p "$(dirname "$plugin")"
curl -fsSL --retry 5 --retry-delay 5 -o "$plugin" \
    "https://github.com/docker/compose/releases/download/$COMPOSE_VERSION/docker-compose-linux-x86_64"
echo "$COMPOSE_SHA256  $plugin" | sha256sum -c -
chmod +x "$plugin"

systemctl enable --now docker
usermod -aG docker ec2-user # sudo 없이 docker 를 쓰게 (다음 로그인부터 적용)
docker compose version
