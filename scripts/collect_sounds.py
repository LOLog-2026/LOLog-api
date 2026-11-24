"""쇼츠 효과음·배경음을 Freesound에서 모아 CLAP 소리 벡터와 함께 메인 백엔드에 넣는다(LOLog-docs sounds.md 2·3·5장, #106).

검색어마다 CC0 소리를 다운로드 수 순으로 찾아 미리 듣기 ogg(고음질)를 받고, CLAP 소리 쪽으로 벡터를 만들어
PUT /internal/sounds/{freesoundId}로 넣는다. 같은 freesoundId로 다시 넣으면 덮어쓰니 여러 번 돌려도 된다.
원본 내려받기는 OAuth2가 필요해서 미리 듣기를 쓴다.

- 소리 벡터: 48kHz 모노로 바꿔 CLAP(laion/clap-htsat-unfused) 소리 쪽에 넣는다. CLAP은 10초까지만 보니
  10초보다 긴 소리(배경음 30~60초)는 10초씩 잘라 조각마다 벡터를 만들고 평균을 낸 뒤 길이 1로 맞춘다.
- Freesound API는 하루 2,000번, 분당 60번까지라 요청 사이에 1초 쉰다. 넣은 freesoundId를 STATE 파일에 적어 두고
  다음에 돌릴 때 건너뛰니, 한도에 걸리면 다음 날 같은 명령으로 이어서 돌리면 된다.

쓰는 법(지원 맥, 배포 api는 SSH 터널로 /internal에 닿는다):
    python3 -m venv .venv && .venv/bin/pip install -r scripts/requirements-sounds.txt
    FREESOUND_API_KEY=... API_URL=http://localhost:8080 INTERNAL_TOKEN=... \\
        .venv/bin/python scripts/collect_sounds.py [--per-query 15] [--only door_slam bgm_calm_piano] [--max-total 30]

키와 토큰 값은 화면에 찍지 않고 레포에 넣지 않는다.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
import time
import urllib.parse
import urllib.error
import urllib.request
import uuid
from pathlib import Path

import librosa
import numpy as np
import torch
from transformers import ClapModel, ClapProcessor

MODEL = "laion/clap-htsat-unfused"  # api SOUND_EMBED_MODEL_NAME과 같아야 한다
SAMPLE_RATE = 48_000
CHUNK_SECONDS = 10
MIN_TAIL_SECONDS = 2  # 마지막 조각이 이보다 짧으면 앞 조각이 있을 때 버린다
DURATION = {"SFX": "duration:[0 TO 5]", "BGM": "duration:[30 TO 60]"}  # 효과음 5초 이하, 배경음 30~60초(sounds.md 3장)
HERE = Path(__file__).resolve().parent


def env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        sys.exit(f"{name}이 필요합니다(환경 변수로만 받아요)")
    return value


def read_queries(path: Path) -> list[tuple[str, str, str]]:
    """검색어 목록(값, 종류, Freesound 검색 글)."""
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        query, kind, text = line.split("\t")
        rows.append((query, kind, text))
    return rows


def freesound_search(key: str, text: str, kind: str, per_query: int) -> list[dict]:
    params = urllib.parse.urlencode({
        "query": text,
        "filter": f'license:"Creative Commons 0" {DURATION[kind]}',
        "sort": "downloads_desc",
        "fields": "id,name,duration,previews,url,license",
        "page_size": per_query,
    })
    req = urllib.request.Request(f"https://freesound.org/apiv2/search/text/?{params}",
                                 headers={"Authorization": f"Token {key}"})
    with urllib.request.urlopen(req, timeout=30) as res:
        data = json.load(res)
    out = []
    for s in data.get("results", []):
        # CC0가 아닌 것은 한 번 더 거른다.
        if "publicdomain/zero" not in s.get("license", ""):
            continue
        preview = (s.get("previews") or {}).get("preview-hq-ogg")
        if preview:
            out.append(s)
    return out


def download(key: str, url: str, path: Path) -> None:
    req = urllib.request.Request(url, headers={"Authorization": f"Token {key}"})
    with urllib.request.urlopen(req, timeout=60) as res:
        path.write_bytes(res.read())


class SoundEmbedder:
    """CLAP 소리 쪽 벡터(길이 1)."""

    def __init__(self):
        self.model = ClapModel.from_pretrained(MODEL).eval()
        self.processor = ClapProcessor.from_pretrained(MODEL)

    def embed(self, path: Path) -> list[float]:
        audio, _ = librosa.load(str(path), sr=SAMPLE_RATE, mono=True)
        if audio.size == 0:
            raise ValueError("빈 소리")
        size = CHUNK_SECONDS * SAMPLE_RATE
        chunks = [audio[i:i + size] for i in range(0, len(audio), size)]
        if len(chunks) > 1 and len(chunks[-1]) < MIN_TAIL_SECONDS * SAMPLE_RATE:
            chunks.pop()
        vectors = []
        for chunk in chunks:
            inputs = self.processor(audio=chunk, sampling_rate=SAMPLE_RATE, return_tensors="pt")
            with torch.no_grad():
                v = self.model.get_audio_features(**inputs)[0].numpy()
            vectors.append(v / np.linalg.norm(v))
        mean = np.mean(vectors, axis=0)
        return [float(x) for x in mean / np.linalg.norm(mean)]


def put_sound(api_url: str, token: str, sound: dict, kind: str, query: str, ogg: Path, embedding: list[float]) -> int:
    """multipart PUT. 응답 상태 코드를 돌려준다."""
    boundary = uuid.uuid4().hex
    fields = {
        "kind": kind,
        "query": query,
        "title": " ".join(sound.get("name", "").split())[:200],
        "durationMs": str(max(1, round(sound["duration"] * 1000))),
        "sourceUrl": sound["url"],
        "license": "CC0",
        "embedding": json.dumps(embedding),
        "embeddingModel": MODEL,
    }
    body = bytearray()
    for name, value in fields.items():
        body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n").encode()
        body += value.encode("utf-8") + b"\r\n"
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sound.ogg\"\r\n"
             "Content-Type: audio/ogg\r\n\r\n").encode()
    body += ogg.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(f"{api_url}/internal/sounds/{sound['id']}", data=bytes(body), method="PUT",
                                 headers={"X-Internal-Token": token,
                                          "Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with urllib.request.urlopen(req, timeout=60) as res:
            return res.status
    except urllib.error.HTTPError as e:
        return e.code


def main() -> None:
    parser = argparse.ArgumentParser(description="Freesound CC0 효과음·배경음을 CLAP 벡터와 함께 api에 넣는다")
    parser.add_argument("--queries", type=Path, default=HERE / "sound-queries.tsv", help="검색어 목록(TSV)")
    parser.add_argument("--per-query", type=int, default=15, help="검색어마다 넣을 개수(명세 10~20)")
    parser.add_argument("--only", nargs="*", default=None, help="이 검색어 값만 모은다")
    parser.add_argument("--max-total", type=int, default=None, help="이만큼 넣으면 멈춘다(시험용)")
    parser.add_argument("--state", type=Path, default=Path.home() / ".lolog-collect-sounds.txt",
                        help="넣은 freesoundId를 적어 두는 파일. 다음에 돌릴 때 건너뛴다")
    args = parser.parse_args()

    key = env("FREESOUND_API_KEY")
    api_url = env("API_URL").rstrip("/")
    token = env("INTERNAL_TOKEN")
    queries = read_queries(args.queries)
    if args.only:
        wanted = set(args.only)
        queries = [q for q in queries if q[0] in wanted]
    done = set(args.state.read_text().split()) if args.state.exists() else set()

    embedder = SoundEmbedder()
    added = 0
    with tempfile.TemporaryDirectory() as work:
        ogg = Path(work) / "sound.ogg"
        for query, kind, text in queries:
            try:
                sounds = freesound_search(key, text, kind, args.per_query)
            except urllib.error.HTTPError as e:
                if e.code == 429:
                    sys.exit(f"Freesound 요청 한도에 걸렸어요. 내일 같은 명령으로 이어서 돌려 주세요(지금까지 {added}개)")
                print(f"{query}: 검색 실패({e.code})", file=sys.stderr)
                continue
            time.sleep(1)
            count = 0
            for sound in sounds:
                sid = str(sound["id"])
                if sid in done:
                    continue
                try:
                    download(key, sound["previews"]["preview-hq-ogg"], ogg)
                    embedding = embedder.embed(ogg)
                except Exception as e:  # noqa: BLE001 - 소리 하나 때문에 전체를 멈추지 않는다
                    print(f"{query}: freesound {sid} 받기·벡터 실패({type(e).__name__})", file=sys.stderr)
                    continue
                status = put_sound(api_url, token, sound, kind, query, ogg, embedding)
                if status != 200:
                    print(f"{query}: freesound {sid} 넣기 실패({status})", file=sys.stderr)
                    continue
                done.add(sid)
                with args.state.open("a") as f:
                    f.write(sid + "\n")
                count += 1
                added += 1
                if args.max_total is not None and added >= args.max_total:
                    print(f"{query}: {count}개\n--max-total에 닿아 멈춰요. 모두 {added}개를 넣었어요.")
                    return
            print(f"{query}: {count}개")
    print(f"모두 {added}개를 넣었어요.")


if __name__ == "__main__":
    main()
