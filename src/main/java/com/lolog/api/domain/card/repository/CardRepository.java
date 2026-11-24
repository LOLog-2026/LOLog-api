package com.lolog.api.domain.card.repository;

import com.lolog.api.domain.card.entity.Card;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// 카드 조회와 만화·영상 횟수 바꾸기.
public interface CardRepository extends JpaRepository<Card, Long> {

	// 녹음 하나의 카드를 ID순으로 읽는다.
	List<Card> findByRecordingIdOrderById(Long recordingId);

	// 내 카드 하나를 읽는다.
	Optional<Card> findByIdAndUserId(Long id, Long userId);

	// 고치기·지우기 중에 같은 카드로 만화를 요청하지 못하게 카드 행을 잠근다(#5의 만화 요청도 같은 행을 잠근다).
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Card c where c.id = :id and c.userId = :userId")
	Optional<Card> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	// 최신순(id 내림차순). before가 null이면 처음부터.
	@Query("select c from Card c where c.userId = :userId and (:before is null or c.id < :before) order by c.id desc")
	List<Card> findPage(@Param("userId") Long userId, @Param("before") Long before, Limit limit);

	// 오래된순(id 오름차순). after가 null이면 처음부터.
	@Query("select c from Card c where c.userId = :userId and (:after is null or c.id > :after) order by c.id asc")
	List<Card> findPageOldest(@Param("userId") Long userId, @Param("after") Long after, Limit limit);

	// 만들기 횟수를 3보다 작을 때만 늘린다. 바뀐 줄이 0이면 이미 3번 만든 카드다. 조건을 붙인 UPDATE 한 줄이라 동시에 눌러도 3을 넘지 않는다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "update cards set comic_count = comic_count + 1 where id = :id and comic_count < 3", nativeQuery = true)
	int incrementComicCount(@Param("id") Long id);

	// 만화가 실패하면 횟수를 돌려준다(실패한 만화는 세지 않음).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "update cards set comic_count = comic_count - 1 where id = :id and comic_count > 0", nativeQuery = true)
	int decrementComicCount(@Param("id") Long id);

	// 영상 만들기 횟수를 2보다 작을 때만 늘린다. 바뀐 줄이 0이면 이미 2번 만든 카드다(만화와 같은 방식).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "update cards set video_count = video_count + 1 where id = :id and video_count < 2", nativeQuery = true)
	int incrementVideoCount(@Param("id") Long id);

	// 영상이 실패하면 횟수를 돌려준다(실패한 영상은 세지 않음).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "update cards set video_count = video_count - 1 where id = :id and video_count > 0", nativeQuery = true)
	int decrementVideoCount(@Param("id") Long id);

	// 블로그 글 카드가 하나라도 있는지 본다(보관함 새 소식 점, texts.md 2장).
	@Query("select count(c) > 0 from Card c where c.userId = :userId "
			+ "and c.textId in (select t.id from Text t where t.source = com.lolog.api.domain.text.entity.TextSource.BLOG)")
	boolean existsBlogCard(@Param("userId") Long userId);

	// since 뒤에 생긴 블로그 글 카드가 있는지 본다.
	@Query("select count(c) > 0 from Card c where c.userId = :userId and c.createdAt > :since "
			+ "and c.textId in (select t.id from Text t where t.source = com.lolog.api.domain.text.entity.TextSource.BLOG)")
	boolean existsBlogCardSince(@Param("userId") Long userId, @Param("since") OffsetDateTime since);

	// 녹음의 카드 중 아직 구간 음성이 없는 카드(id 순서).
	@Query("select c.id from Card c where c.recordingId = :recordingId and c.audioKey is null order by c.id")
	List<Long> findIdsWithoutAudio(@Param("recordingId") Long recordingId);

	// 구간 음성이 아직 없을 때만 붙인다. 바뀐 줄이 0이면 그 사이 다른 쪽이 먼저 붙였거나 카드가 지워졌다.
	// 자르기는 요청이 끝난 뒤 따로 돌아서 부르는 쪽에 트랜잭션이 없으므로 이 메서드가 트랜잭션을 연다.
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			update cards set audio_key = :audioKey, audio_duration_ms = :durationMs, updated_at = current_timestamp
			where id = :id and audio_key is null
			""", nativeQuery = true)
	int attachAudio(@Param("id") Long id, @Param("audioKey") String audioKey, @Param("durationMs") Long durationMs);

	// 모든 카드 화자에서 이 프로필 캐릭터(회원 ID를 글자로)를 뗀다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update CardSpeaker s set s.characterType = null, s.characterId = null, s.updatedAt = current_timestamp
			where s.characterType = 'PROFILE' and s.characterId = :characterId
			""")
	int detachProfileCharacter(@Param("characterId") String characterId);

	// 두 사람이 서로의 카드 화자에서 빠진다. a·b는 회원 ID, aId·bId는 같은 ID를 글자로 적은 것이다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update CardSpeaker s set s.characterType = null, s.characterId = null, s.updatedAt = current_timestamp
			where s.characterType = 'PROFILE' and exists (select c.id from Card c where c.id = s.card.id
			  and ((c.userId = :a and s.characterId = :bId) or (c.userId = :b and s.characterId = :aId)))
			""")
	int detachProfileCharactersBetween(@Param("a") Long a, @Param("aId") String aId, @Param("b") Long b,
			@Param("bId") String bId);

	// 카드마다 대사 수를 센다.
	@Query("select l.card.id, count(l) from CardLine l where l.card.id in :cardIds group by l.card.id")
	List<Object[]> countLinesByCardIds(@Param("cardIds") Collection<Long> cardIds);

	// 카드의 화자 key를 모두 읽는다.
	@Query("select s.speakerKey from CardSpeaker s where s.card.id = :cardId")
	List<String> findSpeakerKeys(@Param("cardId") Long cardId);

	// 카드 한 장에서 화자 key를 바꾼다. 그 화자의 대사는 renameLineSpeaker로 함께 바꾼다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update CardSpeaker s set s.speakerKey = :to, s.updatedAt = current_timestamp
			where s.card.id = :cardId and s.speakerKey = :from
			""")
	int renameSpeaker(@Param("cardId") Long cardId, @Param("from") String from, @Param("to") String to);

	// 카드 한 장에서 그 화자의 대사 key를 바꾼다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update CardLine l set l.speakerKey = :to, l.updatedAt = current_timestamp
			where l.card.id = :cardId and l.speakerKey = :from
			""")
	int renameLineSpeaker(@Param("cardId") Long cardId, @Param("from") String from, @Param("to") String to);

	// 회원의 카드를 모두 지운다(탈퇴). 화자·대사는 DB가 함께 지운다(ON DELETE CASCADE).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Card c where c.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
