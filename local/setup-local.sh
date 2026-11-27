#!/usr/bin/env bash
# 로컬 시크릿 만들기 - local-secrets/ 의 파일 이름이 프로퍼티 키입니다 (운영의 /etc/secrets 와 같은 방식, 커밋 금지).
# 이미 있는 파일은 건드리지 않습니다.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p local-secrets

put() { [ -f "local-secrets/$1" ] || { printf '%s' "$2" > "local-secrets/$1"; echo "만듦: local-secrets/$1"; }; }

put db.password "studio-local-pw"
put redis.password "studio-local-redis"
# 내부 토큰 시크릿은 다른 서비스와 같아야 합니다 - 옆 저장소에 로컬 시크릿이 있으면 그 값을 씁니다
if [ -f ../concert-service/local-secrets/internal.shared-secret ]; then
  put internal.shared-secret "$(cat ../concert-service/local-secrets/internal.shared-secret)"
else
  put internal.shared-secret "$(openssl rand -hex 32)"
fi
put gpu.token "$(openssl rand -hex 16)"
# 크롤러 -> Studio 토큰 키 (/internal/crawl/**) - internal.shared-secret 과 다른 값. 로컬 크롤러는 이 파일을 CRAWLER_SECRETS_DIR 에 복사해 씁니다
put crawler.shared-secret "$(openssl rand -hex 32)"
# 이미지 버킷(LocalStack) 키 - LocalStack 은 값을 따지지 않음
put image.access-key test
put image.secret-key test
# 관리자 계정 - 줄마다 아이디,이름,역할,메일(;),bcrypt (AdminSeeder, DB 에 없는 아이디만 만듭니다). 비밀번호 원문은 로컬이라 주석 줄에 남깁니다
if [ ! -f local-secrets/bootstrap.admins ]; then
  PW="$(openssl rand -hex 9)"
  printf '# 로컬 마스터 — 아이디 master · 비밀번호 %s · 인증 코드 메일은 Mailpit http://localhost:8025\nmaster,마스터,MASTER,master@studio.local,%s\n' \
    "$PW" "$(printf '%s' "$PW" | htpasswd -niB -C 10 x | cut -d: -f2- | tr -d '\n')" > local-secrets/bootstrap.admins
  echo "만듦: local-secrets/bootstrap.admins"
fi

echo
head -1 local-secrets/bootstrap.admins
