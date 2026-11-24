package com.lolog.api.domain.sound.repository;

import com.lolog.api.domain.sound.entity.Sound;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 효과음·배경음 DB 조회. 벡터 칸(embedding, pgvector)은 엔티티에 두지 않고 native query로만 읽고 쓴다.
public interface SoundRepository extends JpaRepository<Sound, Long> {

	// 같은 freesoundId가 있으면 덮어쓰고, 없으면 새로 넣는다. 넣거나 바꾼 줄의 id를 돌려준다.
	// object_key는 id로 정해지니 처음 넣을 때는 빈 값이고, 바꿀 때는 그대로 둔다.
	// embedding은 '[0.1,0.2,...]' 모양의 글로 받아 vector로 바꾼다.
	@Query(value = """
			insert into sound_library (kind, query, title, object_key, duration_ms, freesound_id, source_url, license,
			    embedding, embedding_model)
			values (:kind, :query, :title, '', :durationMs, :freesoundId, :sourceUrl, 'CC0',
			    cast(:embedding as vector), :embeddingModel)
			on conflict (freesound_id) do update set kind = excluded.kind, query = excluded.query, title = excluded.title,
			    duration_ms = excluded.duration_ms, source_url = excluded.source_url, embedding = excluded.embedding,
			    embedding_model = excluded.embedding_model
			returning id
			""", nativeQuery = true)
	Long upsert(@Param("freesoundId") Long freesoundId, @Param("kind") String kind, @Param("query") String query,
			@Param("title") String title, @Param("durationMs") Integer durationMs, @Param("sourceUrl") String sourceUrl,
			@Param("embedding") String embedding, @Param("embeddingModel") String embeddingModel);

	// 그 검색어로 모은 소리 목록(id 순서). ai가 search로 바꾸기 전까지만 쓴다.
	List<Sound> findByKindAndQueryOrderById(String kind, String query);

	// 글 벡터와 가까운 소리(코사인 거리 순). 인덱스 없이 모두 비교한다(V23).
	// 같은 모델로 만든 벡터만 보고, maxDistance보다 먼 소리는 뺀다. 거리가 같으면 id 순서다.
	@Query(value = """
			select id, kind, duration_ms as durationMs, object_key as objectKey,
			    embedding <=> cast(:embedding as vector) as distance
			from sound_library
			where kind = :kind and embedding_model = :embeddingModel and embedding is not null
			    and embedding <=> cast(:embedding as vector) <= :maxDistance
			order by distance, id
			limit :limit
			""", nativeQuery = true)
	List<SoundMatch> searchNearest(@Param("kind") String kind, @Param("embedding") String embedding,
			@Param("embeddingModel") String embeddingModel, @Param("maxDistance") double maxDistance,
			@Param("limit") int limit);

	// 검색 결과 한 줄.
	interface SoundMatch {

		Long getId();

		String getKind();

		Integer getDurationMs();

		String getObjectKey();

		Double getDistance();

	}

	// 소리 하나를 지우고 그 파일 키를 돌려준다. 없으면 비어 있다. 한 문장이라 동시에 빼도 오류가 나지 않는다.
	@Query(value = "delete from sound_library where freesound_id = :freesoundId returning object_key", nativeQuery = true)
	Optional<String> deleteByFreesoundIdReturningKey(@Param("freesoundId") Long freesoundId);
}
