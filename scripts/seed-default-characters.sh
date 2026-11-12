#!/usr/bin/env bash
# 기본 화자 목록을 메인 백엔드에 넣는다(LOLog-docs characters.md 2장).
# LOLog-docs assets/default-characters/characters.json의 순서대로 PUT /internal/characters/defaults/{id}를 부른다.
# 같은 id로 다시 넣으면 덮어쓰니 여러 번 돌려도 된다.
#
# 쓰는 법:
#   API_URL=http://localhost:8080 INTERNAL_TOKEN=... scripts/seed-default-characters.sh [assets 폴더]
#   그림이 이미 저장소에 올라가 있으면 SKIP_IMAGES=1을 붙여 목록만 넣는다(배포 MinIO는 11/11에 올려 둠).
#   목소리(characters.md 기본 화자 목소리)는 VOICES_DIR에 {id}.wav가 있으면 같이 올린다(예: VOICES_DIR=../tts/default-voices).
#   VOICES_DIR이 없거나 그 화자의 파일이 없으면 목소리는 지금 붙어 있는 그대로 둔다.
# 토큰 값은 화면에 찍지 않는다.
set -euo pipefail

assets="${1:-$(dirname "$0")/../../LOLog-docs/assets/default-characters}"
: "${API_URL:?API_URL이 필요합니다(예: http://localhost:8080)}"
: "${INTERNAL_TOKEN:?INTERNAL_TOKEN이 필요합니다}"
json="$assets/characters.json"
if [ ! -f "$json" ]; then
	echo "$json 이 없습니다." >&2
	exit 1
fi

# id, ageGroup, gender, glasses, label, appearance, image, thumbnail, 순서를 탭으로 나눠 한 줄씩 꺼낸다.
rows=$(python3 -I -c '
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
for i, c in enumerate(data["characters"]):
    print("\t".join([c["id"], c["ageGroup"], c["gender"], str(c["glasses"]).lower(), c["label"], c["appearance"],
                     c["image"], c["thumbnail"], str(i + 1)]))
' "$json")

ok=0
fail=0
while IFS=$'\t' read -r id age gender glasses label appearance image thumbnail order; do
	args=(-s -o /dev/null -w '%{http_code}' -X PUT -H "X-Internal-Token: ${INTERNAL_TOKEN}"
		-F "ageGroup=${age}" -F "gender=${gender}" -F "glasses=${glasses}" -F "sortOrder=${order}"
		--form-string "label=${label}" --form-string "appearance=${appearance}")
	if [ "${SKIP_IMAGES:-0}" != "1" ]; then
		args+=(-F "image=@${assets}/${image};type=image/png" -F "thumbnail=@${assets}/${thumbnail};type=image/webp")
	fi
	if [ -n "${VOICES_DIR:-}" ] && [ -f "${VOICES_DIR}/${id}.wav" ]; then
		args+=(-F "voice=@${VOICES_DIR}/${id}.wav;type=audio/wav")
	fi
	code=$(curl "${args[@]}" "${API_URL}/internal/characters/defaults/${id}")
	if [ "$code" = "204" ]; then
		ok=$((ok + 1))
	else
		fail=$((fail + 1))
		echo "실패: ${id} (HTTP ${code})" >&2
	fi
done <<< "$rows"
echo "완료: 성공 ${ok}명, 실패 ${fail}명"
