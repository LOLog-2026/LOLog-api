#!/usr/bin/env bash
# 로컬 전용: 인증 서비스 없이 시험할 때 쓸 액세스 토큰(JWT)을 만든다(API 명세 "토큰").
# .env의 JWT_SECRET으로 HS256 서명하고, sub=사용자 ID, typ=access, 1주일 뒤 만료.
# 쓰는 법: scripts/dev-token.sh [사용자 ID, 기본 1]
# 나온 토큰은 Authorization: Bearer <토큰>으로 보낸다. 배포 서버의 비밀값으로는 쓰지 않는다.
set -euo pipefail

cd "$(dirname "$0")/.."
user_id="${1:-1}"
if ! [[ "$user_id" =~ ^[0-9]+$ ]]; then
	echo "사용자 ID는 숫자여야 합니다." >&2
	exit 1
fi
secret="$(grep '^JWT_SECRET=' .env | cut -d= -f2-)"
if [ "${#secret}" -lt 32 ]; then
	echo ".env에 32바이트 이상의 JWT_SECRET이 필요합니다." >&2
	exit 1
fi

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now="$(date +%s)"
exp="$((now + 7 * 24 * 60 * 60))"
header="$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)"
payload="$(printf '{"sub":"%s","typ":"access","iat":%d,"exp":%d}' "$user_id" "$now" "$exp" | b64url)"
signature="$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$secret" -binary | b64url)"
echo "$header.$payload.$signature"
