"""CLAP 글 쪽을 api가 쓸 ONNX로 바꾼다(LOLog-docs sounds.md 5장 "api 안에서 글 벡터 만들기", #106).

텍스트 인코더(RoBERTa)와 투영(글 벡터를 소리 벡터와 같은 공간으로 옮기는 마지막 층)을 함께 내보낸다.
투영이 빠지면 오류 없이 512개 숫자가 나오지만 소리 벡터와 비교할 수 없으니, 내보낸 뒤 PyTorch 결과와 같은지 꼭 확인한다.

쓰는 법(지원 맥에서 한 번, 모델을 바꿀 때만 다시):
    python3 -m venv .venv && .venv/bin/pip install -r scripts/requirements-sounds.txt
    .venv/bin/python scripts/export_clap_text.py OUT_DIR

OUT_DIR에 text_model.onnx, tokenizer.json, reference.json(확인용 글과 벡터)이 생긴다. 이 폴더를 서버에 올리고
api 환경 변수 SOUND_EMBED_MODEL_DIR로 가리킨다. 모델 파일은 레포에 넣지 않는다.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import torch
from transformers import ClapModel, ClapProcessor

MODEL = "laion/clap-htsat-unfused"
MAX_TOKENS = 77  # api ClapTextEmbedder.MAX_TOKENS와 같아야 한다
# 확인용 글. api 테스트(ClapTextEmbedderTests)가 같은 글로 Java 결과를 비교한다.
REFERENCE_TEXTS = [
    "door slamming loudly",
    "a dog barking in the distance",
    "calm piano music for a quiet afternoon",
    "crowd laughing and clapping",
]


class TextTower(torch.nn.Module):
    """input_ids, attention_mask → 투영까지 거친 글 벡터(길이 1로 맞추기 전)."""

    def __init__(self, model: ClapModel):
        super().__init__()
        self.text_model = model.text_model
        self.text_projection = model.text_projection

    def forward(self, input_ids, attention_mask):
        out = self.text_model(input_ids=input_ids, attention_mask=attention_mask)
        return self.text_projection(out.pooler_output)


def tokenize(processor: ClapProcessor, texts: list[str]):
    return processor.tokenizer(texts, truncation=True, max_length=MAX_TOKENS, return_tensors="pt", padding=True)


def main(out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    model = ClapModel.from_pretrained(MODEL).eval()
    processor = ClapProcessor.from_pretrained(MODEL)
    tower = TextTower(model).eval()

    sample = tokenize(processor, ["door slamming"])
    onnx_path = out_dir / "text_model.onnx"
    torch.onnx.export(
        tower,
        (sample["input_ids"], sample["attention_mask"]),
        str(onnx_path),
        input_names=["input_ids", "attention_mask"],
        output_names=["text_embeds"],
        dynamic_axes={"input_ids": {0: "batch", 1: "tokens"}, "attention_mask": {0: "batch", 1: "tokens"},
                      "text_embeds": {0: "batch"}},
        opset_version=17,
        dynamo=False,
    )
    processor.tokenizer.backend_tokenizer.save(str(out_dir / "tokenizer.json"))

    # PyTorch(ClapModel.get_text_features)와 ONNX 결과가 같은지 확인하고, Java 비교용 벡터를 남긴다.
    import onnxruntime as ort

    session = ort.InferenceSession(str(onnx_path))
    refs = []
    for text in REFERENCE_TEXTS:
        enc = tokenize(processor, [text])
        with torch.no_grad():
            expected = model.get_text_features(**enc)[0].numpy()
        got = session.run(None, {"input_ids": enc["input_ids"].numpy(), "attention_mask": enc["attention_mask"].numpy()})[0][0]
        expected /= np.linalg.norm(expected)
        got = got / np.linalg.norm(got)
        cos = float(np.dot(expected, got))
        if cos < 0.9999:
            sys.exit(f"ONNX 결과가 PyTorch와 다릅니다: {text!r} 코사인 {cos:.6f}")
        refs.append({"text": text, "embedding": [float(x) for x in expected]})
    (out_dir / "reference.json").write_text(json.dumps({"model": MODEL, "references": refs}), encoding="utf-8")
    print(f"만들었어요: {out_dir} (PyTorch와 ONNX 코사인 0.9999 이상 확인)")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("쓰는 법: export_clap_text.py OUT_DIR")
    main(Path(sys.argv[1]))
