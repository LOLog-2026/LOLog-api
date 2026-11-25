package com.lolog.api.domain.text.repository;

import com.lolog.api.domain.text.entity.Text;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 글 DB 조회.
public interface TextRepository extends JpaRepository<Text, Long> {

	// 글(별칭 t)의 첫 카드 ID. 카드가 없으면 null.
	String FIRST_CARD_JPQL = "(select min(c.id) from Card c where c.textId = t.id)";

	// 글(별칭 t)의 상태. 그 글로 만든 카드가 있으면 DONE, 작업이 실패면 FAILED, 그 밖에는 QUEUED(texts.md 2장 "상태 채우기").
	// 블로그 글 목록도 이것으로 채운다.
	String STATUS_JPQL = """
			case when exists (select 1 from Card c where c.textId = t.id) then 'DONE'
			     when exists (select 1 from Job j where j.textId = t.id and j.status = 'FAILED') then 'FAILED'
			     else 'QUEUED' end""";

	/**
	 * 워커가 같은 글의 카드를 동시에 보내도 하나씩 받게 글 행을 잠근다.
	 *
	 * @param id 글 ID
	 * @return 잠근 글. 없으면 빈 값
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Text t where t.id = :id")
	Optional<Text> findByIdForUpdate(@Param("id") Long id);

	/**
	 * 내 글 하나를 찾는다.
	 *
	 * @param id 글 ID
	 * @param userId 글 주인이어야 하는 회원 ID
	 * @return 글. 없거나 주인이 다르면 빈 값
	 */
	Optional<Text> findByIdAndUserId(Long id, Long userId);

	/**
	 * 내 글 하나를 첫 카드·상태와 블로그 글 정보(원문 링크, 블로그 이름)와 함께 읽는다.
	 *
	 * @param id 글 ID
	 * @param userId 글 주인이어야 하는 회원 ID
	 * @return 글 보기. 없거나 주인이 다르면 빈 값
	 */
	@Query("select new com.lolog.api.domain.text.repository.TextView(t.id, t.source, t.title, t.body, t.place, t.situation,"
			+ " t.truncated, t.createdAt, " + FIRST_CARD_JPQL + ", " + STATUS_JPQL + ", p.link, p.blogTitle)"
			+ " from Text t left join BlogPost p on p.id = t.blogPostId where t.id = :id and t.userId = :userId")
	Optional<TextView> findView(@Param("id") Long id, @Param("userId") Long userId);

	/**
	 * 회원의 글을 모두 지운다(탈퇴).
	 *
	 * @param userId 탈퇴하는 회원 ID
	 * @return 지운 글 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Text t where t.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
