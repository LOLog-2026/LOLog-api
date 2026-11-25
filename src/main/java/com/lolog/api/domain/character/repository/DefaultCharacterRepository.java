package com.lolog.api.domain.character.repository;

import com.lolog.api.domain.character.entity.DefaultCharacter;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 기본 화자 DB 조회.
public interface DefaultCharacterRepository extends JpaRepository<DefaultCharacter, String> {

	/**
	 * 기본 화자 하나를 넣거나 바꾼다. voiceKey가 null이면 지금 붙어 있는 목소리를 그대로 둔다.
	 *
	 * @param id 기본 화자 id(예: young-f-02)
	 * @param ageGroup 나이대(CHILD, YOUNG, MIDDLE)
	 * @param gender 성별(F, M)
	 * @param glasses 안경을 썼으면 true
	 * @param label 앱에 보여 줄 화자 이름(50자 이하)
	 * @param appearance 그림 모델용 생김새 설명(200자 이하)
	 * @param imageKey 원본 그림 저장소 키
	 * @param thumbKey 줄인 그림 저장소 키
	 * @param voiceKey 목소리 파일 저장소 키(null이면 지금 목소리를 그대로 둠)
	 * @param sortOrder 목록에서의 순서
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			insert into default_characters (id, age_group, gender, glasses, label, appearance, image_key, thumb_key,
			    voice_key, sort_order)
			values (:id, :ageGroup, :gender, :glasses, :label, :appearance, :imageKey, :thumbKey, :voiceKey, :sortOrder)
			on conflict (id) do update set age_group = excluded.age_group, gender = excluded.gender,
			    glasses = excluded.glasses, label = excluded.label, appearance = excluded.appearance,
			    image_key = excluded.image_key, thumb_key = excluded.thumb_key,
			    voice_key = coalesce(excluded.voice_key, default_characters.voice_key), sort_order = excluded.sort_order,
			    updated_at = current_timestamp
			""", nativeQuery = true)
	void upsert(@Param("id") String id, @Param("ageGroup") String ageGroup, @Param("gender") String gender,
			@Param("glasses") boolean glasses, @Param("label") String label, @Param("appearance") String appearance,
			@Param("imageKey") String imageKey, @Param("thumbKey") String thumbKey, @Param("voiceKey") String voiceKey,
			@Param("sortOrder") Integer sortOrder);

	// 정한 순서(sort_order, id)대로 모두.
	List<DefaultCharacter> findAllByOrderBySortOrderAscIdAsc();

	/**
	 * 목소리가 붙어 있는 기본 화자인지 본다.
	 *
	 * @param id 기본 화자 id
	 * @return 목소리가 붙어 있으면 true
	 */
	boolean existsByIdAndVoiceKeyIsNotNull(String id);

}
