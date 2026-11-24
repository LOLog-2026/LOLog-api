package com.lolog.api.domain.sound.service;

// 콘티의 소리 설명 글("door slamming" 등)을 소리 벡터와 비교할 수 있는 글 벡터로 바꾼다(sounds.md 5장).
// 실제로는 ClapTextEmbedder가 CLAP 글 쪽 ONNX 모델로 만들고, 테스트에서는 가짜로 바꿔 끼운다.
public interface SoundTextEmbedder {

	// 길이 1로 맞춘 512차원 글 벡터. 모델을 읽지 못하면 503 EMBEDDING_UNAVAILABLE을 던진다.
	float[] embed(String text);

	// 벡터를 만든 모델 이름. 같은 모델로 만든 소리 벡터끼리만 비교한다.
	String modelName();

}
