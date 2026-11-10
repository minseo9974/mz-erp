#!/usr/bin/env bash
# 운영 서버 최초 1회 준비 (Oracle Cloud, Ubuntu 24.04, Ampere A1/arm64 기준)
#
#   scp infra/server/setup.sh ubuntu@<서버IP>:~
#   ssh ubuntu@<서버IP> 'sudo bash ~/setup.sh "<배포용 공개키 한 줄>"'
#
# 하는 일: Docker 설치 · deploy 사용자 생성 · 80/443 방화벽 열기 · /opt/mz-erp 준비 · DB 백업 cron
set -euo pipefail

DEPLOY_PUBKEY="${1:?사용법: sudo bash setup.sh \"ssh-ed25519 AAAA... mz-erp-deploy\"}"
APP_DIR=/opt/mz-erp

echo "== 시간대"
timedatectl set-timezone Asia/Seoul

echo "== Docker 설치 (공식 apt 저장소)"
if ! command -v docker >/dev/null; then
  apt-get update -y
  apt-get install -y ca-certificates curl
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker

echo "== deploy 사용자 (CD가 SSH로 접속하는 계정, docker만 다룸)"
if ! id deploy >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash deploy
fi
usermod -aG docker deploy
install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
echo "$DEPLOY_PUBKEY" > /home/deploy/.ssh/authorized_keys
chown deploy:deploy /home/deploy/.ssh/authorized_keys
chmod 600 /home/deploy/.ssh/authorized_keys

echo "== 앱 디렉터리"
install -d -m 750 -o deploy -g deploy "$APP_DIR" "$APP_DIR/backups"

echo "== 방화벽: Oracle Ubuntu 이미지는 iptables가 22번 외에 모두 막는다 → 80/443 허용"
for port in 80 443; do
  if ! iptables -C INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT 2>/dev/null; then
    iptables -I INPUT 6 -p tcp --dport "$port" -m state --state NEW -j ACCEPT
  fi
done
if command -v netfilter-persistent >/dev/null; then
  netfilter-persistent save
fi

echo "== DB 백업 cron (매일 03:00, 7일 보관)"
cat > "$APP_DIR/backup.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cd /opt/mz-erp
docker compose exec -T db pg_dump -U mzerp -d mzerp --format=custom > "backups/mzerp-$(date +%Y%m%d-%H%M).dump"
find backups -name 'mzerp-*.dump' -mtime +7 -delete
EOF
chown deploy:deploy "$APP_DIR/backup.sh"
chmod 750 "$APP_DIR/backup.sh"
echo "0 3 * * * deploy /opt/mz-erp/backup.sh >> /opt/mz-erp/backups/backup.log 2>&1" > /etc/cron.d/mz-erp-backup

echo
echo "완료. 다음: $APP_DIR/.env 를 만들고 (infra/server/.env.example 참고, chmod 600, 소유자 deploy)"
echo "      Oracle 콘솔 보안 목록(Security List)에서 80/443 인바운드도 열어야 합니다."
