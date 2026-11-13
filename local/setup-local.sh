#!/usr/bin/env bash
# 로컬 시크릿 만들기 — local-secrets/ 의 파일 이름이 프로퍼티 키입니다 (운영의 /etc/secrets 와 같은 방식 · 커밋 금지).
# 이미 있는 파일은 건드리지 않습니다.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p local-secrets

put() { [ -f "local-secrets/$1" ] || { printf '%s' "$2" > "local-secrets/$1"; echo "만듦: local-secrets/$1"; }; }

put db.password "studio-local-pw"
# 내부 토큰 시크릿은 다른 서비스와 같아야 합니다 — 옆 저장소에 로컬 시크릿이 있으면 그 값을 씁니다
if [ -f ../concert-service/local-secrets/internal.shared-secret ]; then
  put internal.shared-secret "$(cat ../concert-service/local-secrets/internal.shared-secret)"
else
  put internal.shared-secret "$(openssl rand -hex 32)"
fi
put totp.encryption-key "$(openssl rand -base64 32)"
put gpu.token "$(openssl rand -hex 16)"
put bootstrap.master-password "$(openssl rand -base64 18)"

echo
echo "첫 마스터 계정: master / $(cat local-secrets/bootstrap.master-password)  (계정이 하나도 없을 때만 만들어집니다)"
