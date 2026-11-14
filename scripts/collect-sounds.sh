#!/usr/bin/env bash
# 쇼츠 효과음·배경음을 Freesound에서 모아 메인 백엔드에 넣는다(LOLog-docs sounds.md 2·3·5장, FR-EXT-03).
# 검색어 값마다 CC0 소리를 찾아 미리 듣기 ogg(고음질)를 받고 PUT /internal/sounds/{freesoundId}로 넣는다.
# 같은 freesoundId로 다시 넣으면 덮어쓰니 여러 번 돌려도 된다. 원본 내려받기는 OAuth2가 필요해서 미리 듣기를 쓴다.
#
# 쓰는 법(지원 맥에서 한 번, 배포 api는 SSH 터널로 /internal에 닿는다):
#   FREESOUND_API_KEY=... API_URL=http://localhost:8080 INTERNAL_TOKEN=... scripts/collect-sounds.sh [검색어 값...]
#   검색어 값을 주면 그것만 모은다(예: door_slam bgm_comic). 없으면 아래 목록 전부.
#   PER_QUERY(기본 8)로 검색어마다 넣을 개수를 바꾼다(명세는 5~10개).
# 키와 토큰 값은 화면에 찍지 않고 레포에 넣지 않는다.
set -euo pipefail

: "${FREESOUND_API_KEY:?FREESOUND_API_KEY가 필요합니다(Freesound API 키, 환경 변수로만)}"
: "${API_URL:?API_URL이 필요합니다(예: http://localhost:8080)}"
: "${INTERNAL_TOKEN:?INTERNAL_TOKEN이 필요합니다}"
per_query="${PER_QUERY:-8}"

# 검색어 값(콘티가 고르는 값) → 종류, Freesound 검색 글. 효과음 24개·배경음 5개(sounds.md 3장, 정함 11-14)
# ai 레포 src/lolog_ai/sounds.py의 SFX·BGM과 같아야 한다. 목록을 바꿀 때는 둘 다 같이 바꾼다.
catalog() {
	cat <<'EOF'
door_slam	SFX	door slam
footsteps	SFX	footsteps
laugh	SFX	laugh
applause	SFX	applause
phone_notify	SFX	phone notification
keyboard	SFX	keyboard typing
dishes	SFX	dishes cup clink
car_horn	SFX	car horn
rain	SFX	rain
wind	SFX	wind
dog	SFX	dog bark
cat	SFX	cat meow
pop	SFX	pop
fail	SFX	fail sound
success	SFX	success jingle
rimshot	SFX	rimshot
whoosh	SFX	whoosh
thud	SFX	thud
sigh	SFX	sigh
gasp	SFX	gasp surprise
ding	SFX	ding idea bell
cafe	SFX	cafe ambience
classroom	SFX	classroom ambience
crowd	SFX	crowd street ambience
bgm_upbeat	BGM	upbeat music loop
bgm_calm	BGM	calm music loop
bgm_tense	BGM	tense music loop
bgm_sad	BGM	sad music loop
bgm_comic	BGM	comic music loop
EOF
}

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

wanted=" $* "
added=0
while IFS=$'\t' read -r query kind text; do
	if [ "$#" -gt 0 ] && [[ "$wanted" != *" $query "* ]]; then
		continue
	fi
	# 효과음 5초 이하, 배경음 30~60초(sounds.md 3장)
	if [ "$kind" = "SFX" ]; then
		duration="duration:[0 TO 5]"
	else
		duration="duration:[30 TO 60]"
	fi
	curl -sS -G --fail "https://freesound.org/apiv2/search/text/" \
		-H "Authorization: Token $FREESOUND_API_KEY" \
		--data-urlencode "query=$text" \
		--data-urlencode "filter=license:\"Creative Commons 0\" $duration" \
		--data-urlencode "fields=id,name,duration,previews,url,license" \
		--data-urlencode "page_size=$per_query" \
		-o "$work/search.json"

	# id, 제목, 길이(ms), 미리 듣기 주소, 페이지 주소를 탭으로 나눠 한 줄씩. CC0가 아닌 것은 한 번 더 거른다.
	rows=$(python3 -I -c '
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
for s in data.get("results", []):
    if "publicdomain/zero" not in s.get("license", ""):
        continue
    preview = s.get("previews", {}).get("preview-hq-ogg")
    if not preview:
        continue
    name = " ".join(s.get("name", "").split())[:200]
    print("\t".join([str(s["id"]), name, str(max(1, round(s["duration"] * 1000))), preview, s["url"]]))
' "$work/search.json")

	count=0
	while IFS=$'\t' read -r id name ms preview page; do
		[ -n "$id" ] || continue
		curl -sS --fail -H "Authorization: Token $FREESOUND_API_KEY" -o "$work/sound.ogg" "$preview"
		status=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT "$API_URL/internal/sounds/$id" \
			-H "X-Internal-Token: $INTERNAL_TOKEN" \
			-F "file=@$work/sound.ogg;type=audio/ogg" \
			--form-string "kind=$kind" \
			--form-string "query=$query" \
			--form-string "title=$name" \
			--form-string "durationMs=$ms" \
			--form-string "sourceUrl=$page" \
			--form-string "license=CC0")
		if [ "$status" != "200" ]; then
			echo "$query: freesound $id 넣기 실패($status)" >&2
			continue
		fi
		count=$((count + 1))
	done <<< "$rows"
	echo "${query}: ${count}개"
	added=$((added + count))
	# Freesound API 요청 한도(분당 60회)에 걸리지 않게 쉰다.
	sleep 1
done < <(catalog)

echo "모두 ${added}개를 넣었습니다."
