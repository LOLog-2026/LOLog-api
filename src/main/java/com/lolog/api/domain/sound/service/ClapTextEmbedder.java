package com.lolog.api.domain.sound.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// CLAP(laion/clap-htsat-unfused) 글 쪽을 ONNX로 돌려 글 벡터를 만든다(sounds.md 5장 "api 안에서 글 벡터 만들기").
// 모델 폴더(SOUND_EMBED_MODEL_DIR)에는 scripts/export_clap_text.py가 만든 text_model.onnx와 tokenizer.json이 있다.
// 모델은 처음 검색할 때 올려 두고 계속 쓴다. 폴더가 비었거나 읽지 못하면 검색만 503이고 서버는 뜬다.
@Component
public class ClapTextEmbedder implements SoundTextEmbedder {

	private static final Logger log = LoggerFactory.getLogger(ClapTextEmbedder.class);

	public static final int DIMENSIONS = 512;
	// CLAP 글 쪽이 학습한 최대 토큰 수. export_clap_text.py와 같아야 한다.
	static final int MAX_TOKENS = 77;
	// 같은 설명 글이 자주 와서 최근 글의 벡터를 둔다.
	static final int CACHE_SIZE = 500;
	// 모델을 읽지 못했을 때 바로 다시 읽지 않고 이만큼 기다린다(망가진 파일을 검색마다 다시 읽지 않게).
	static final Duration RETRY_AFTER = Duration.ofMinutes(1);

	private final Path modelDir;
	private final String modelName;
	private final Map<String, float[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
			return size() > CACHE_SIZE;
		}
	};

	private Model model;
	private Instant failedAt;

	// 모델 폴더와 모델 이름을 설정에서 받는다.
	public ClapTextEmbedder(@Value("${lolog.sound.embed-model-dir:}") String modelDir,
			@Value("${lolog.sound.embed-model-name:laion/clap-htsat-unfused}") String modelName) {
		this.modelDir = modelDir == null || modelDir.isBlank() ? null : Path.of(modelDir.strip());
		this.modelName = modelName;
	}

	@Override
	public String modelName() {
		return modelName;
	}

	@Override
	public float[] embed(String text) {
		synchronized (cache) {
			float[] hit = cache.get(text);
			if (hit != null) {
				return hit.clone();
			}
		}
		float[] vector = model().embed(text);
		synchronized (cache) {
			cache.put(text, vector);
		}
		return vector.clone();
	}

	// 올려 둔 모델. 없으면 읽는다. 읽지 못하면 503이다.
	private synchronized Model model() {
		if (model != null) {
			return model;
		}
		if (modelDir == null) {
			throw new ApiException(ErrorCode.EMBEDDING_UNAVAILABLE);
		}
		if (failedAt != null && Instant.now().isBefore(failedAt.plus(RETRY_AFTER))) {
			throw new ApiException(ErrorCode.EMBEDDING_UNAVAILABLE);
		}
		try {
			model = Model.load(modelDir);
			failedAt = null;
			log.info("CLAP 글 모델을 올렸습니다: {}", modelDir);
			return model;
		}
		catch (Exception e) {
			failedAt = Instant.now();
			log.warn("CLAP 글 모델을 읽지 못했습니다: {} ({})", modelDir, e.toString());
			throw new ApiException(ErrorCode.EMBEDDING_UNAVAILABLE);
		}
	}

	// 서버가 꺼질 때 모델을 내린다.
	@PreDestroy
	synchronized void close() {
		if (model != null) {
			model.close();
			model = null;
		}
	}

	// 토크나이저와 ONNX 세션 한 벌.
	private record Model(HuggingFaceTokenizer tokenizer, OrtSession session) {

		// 모델 폴더에서 text_model.onnx와 tokenizer.json을 읽는다.
		static Model load(Path dir) throws Exception {
			Path onnx = dir.resolve("text_model.onnx");
			Path tokenizerJson = dir.resolve("tokenizer.json");
			if (!Files.isRegularFile(onnx) || !Files.isRegularFile(tokenizerJson)) {
				throw new IllegalStateException("text_model.onnx 또는 tokenizer.json이 없습니다");
			}
			HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(tokenizerJson,
					Map.of("addSpecialTokens", "true", "padding", "false", "truncation", "true", "maxLength",
							String.valueOf(MAX_TOKENS)));
			try {
				OrtSession session = OrtEnvironment.getEnvironment()
					.createSession(onnx.toString(), new OrtSession.SessionOptions());
				return new Model(tokenizer, session);
			}
			catch (OrtException | RuntimeException e) {
				tokenizer.close();
				throw e;
			}
		}

		// 글 하나를 벡터로 바꾸고 길이 1로 맞춘다.
		float[] embed(String text) {
			Encoding encoding = tokenizer.encode(text);
			OrtEnvironment env = OrtEnvironment.getEnvironment();
			try (OnnxTensor ids = OnnxTensor.createTensor(env, new long[][] { encoding.getIds() });
					OnnxTensor mask = OnnxTensor.createTensor(env, new long[][] { encoding.getAttentionMask() });
					OrtSession.Result result = session.run(Map.of("input_ids", ids, "attention_mask", mask))) {
				float[] vector = ((float[][]) result.get(0).getValue())[0];
				if (vector.length != DIMENSIONS) {
					throw new IllegalStateException("글 벡터 길이가 " + vector.length + "입니다");
				}
				return normalize(vector);
			}
			catch (OrtException e) {
				log.warn("CLAP 글 벡터를 만들지 못했습니다 ({})", e.toString());
				throw new ApiException(ErrorCode.EMBEDDING_UNAVAILABLE);
			}
		}

		void close() {
			try {
				session.close();
			}
			catch (OrtException e) {
				log.warn("CLAP 세션을 닫지 못했습니다 ({})", e.toString());
			}
			tokenizer.close();
		}

	}

	// 벡터를 길이 1로 맞춘다. 코사인 거리로 비교하니 소리 벡터와 같은 기준으로 둔다.
	static float[] normalize(float[] vector) {
		double sum = 0;
		for (float v : vector) {
			sum += v * v;
		}
		double norm = Math.sqrt(sum);
		if (norm == 0) {
			throw new IllegalStateException("길이가 0인 벡터입니다");
		}
		float[] out = new float[vector.length];
		for (int i = 0; i < vector.length; i++) {
			out[i] = (float) (vector[i] / norm);
		}
		return out;
	}

}
