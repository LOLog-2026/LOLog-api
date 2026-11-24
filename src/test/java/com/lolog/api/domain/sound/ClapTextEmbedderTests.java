package com.lolog.api.domain.sound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lolog.api.domain.sound.service.ClapTextEmbedder;
import com.lolog.api.global.error.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// CLAP 글 벡터(#106, sounds.md 5장). Java(ONNX Runtime·DJL 토크나이저) 결과가 PyTorch 결과와 같은지 본다.
// 모델 파일은 레포에 없어서, scripts/export_clap_text.py로 만든 폴더를 SOUND_EMBED_MODEL_DIR로 줄 때만 돈다.
class ClapTextEmbedderTests {

	@Test
	void 모델_폴더가_없으면_503() {
		ClapTextEmbedder embedder = new ClapTextEmbedder("", "laion/clap-htsat-unfused");
		assertThatThrownBy(() -> embedder.embed("door slamming")).isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "EMBEDDING_UNAVAILABLE");
		ClapTextEmbedder missing = new ClapTextEmbedder("/nonexistent/clap", "laion/clap-htsat-unfused");
		assertThatThrownBy(() -> missing.embed("door slamming")).isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "EMBEDDING_UNAVAILABLE");
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "SOUND_EMBED_MODEL_DIR", matches = ".+")
	void PyTorch와_같은_글_벡터를_만든다() throws Exception {
		String dir = System.getenv("SOUND_EMBED_MODEL_DIR");
		JsonNode reference = new ObjectMapper().readTree(Files.readString(Path.of(dir, "reference.json")));
		ClapTextEmbedder embedder = new ClapTextEmbedder(dir, reference.get("model").asString());
		for (JsonNode ref : reference.get("references")) {
			float[] got = embedder.embed(ref.get("text").asString());
			assertThat(got).hasSize(ClapTextEmbedder.DIMENSIONS);
			double dot = 0;
			double norm = 0;
			for (int i = 0; i < got.length; i++) {
				dot += got[i] * ref.get("embedding").get(i).asDouble();
				norm += got[i] * got[i];
			}
			assertThat(Math.sqrt(norm)).as("길이 1").isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
			assertThat(dot).as("PyTorch와 코사인 유사도: %s", ref.get("text").asString()).isGreaterThan(0.9999);
		}
		// 같은 글은 캐시에서 같은 값이 나온다.
		assertThat(embedder.embed("door slamming loudly")).isEqualTo(embedder.embed("door slamming loudly"));
	}

}
