package com.lolog.api.domain.sound.repository;

import com.lolog.api.domain.sound.entity.Sound;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 효과음·배경음 DB 조회.
public interface SoundRepository extends JpaRepository<Sound, Long> {

	// 같은 freesoundId가 있으면 덮어쓰고, 없으면 새로 넣는다. 넣거나 바꾼 줄의 id를 돌려준다.
	// object_key는 id로 정해지니 처음 넣을 때는 빈 값이고, 바꿀 때는 그대로 둔다.
	@Query(value = """
			insert into sound_library (kind, query, title, object_key, duration_ms, freesound_id, source_url, license)
			values (:kind, :query, :title, '', :durationMs, :freesoundId, :sourceUrl, 'CC0')
			on conflict (freesound_id) do update set kind = excluded.kind, query = excluded.query, title = excluded.title,
			    duration_ms = excluded.duration_ms, source_url = excluded.source_url
			returning id
			""", nativeQuery = true)
	Long upsert(@Param("freesoundId") Long freesoundId, @Param("kind") String kind, @Param("query") String query,
			@Param("title") String title, @Param("durationMs") Integer durationMs, @Param("sourceUrl") String sourceUrl);

	// 그 검색어로 모은 소리 목록(id 순서).
	List<Sound> findByKindAndQueryOrderById(String kind, String query);

	// 소리 하나를 지우고 그 파일 키를 돌려준다. 없으면 비어 있다. 한 문장이라 동시에 빼도 오류가 나지 않는다.
	@Query(value = "delete from sound_library where freesound_id = :freesoundId returning object_key", nativeQuery = true)
	Optional<String> deleteByFreesoundIdReturningKey(@Param("freesoundId") Long freesoundId);
}
