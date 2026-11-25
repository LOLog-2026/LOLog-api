package com.lolog.api.domain.card.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("CardRepository")
class CardRepositoryTest extends RepositoryTest {

	private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 1, 12, 0, 0, 0, ZoneOffset.UTC);

	@Autowired
	private CardRepository cardRepository;

	@Autowired
	private JdbcTemplate jdbc;

	// 녹음 카드 한 장을 넣는다.
	private long card(long userId, Long recordingId) {
		return jdbc.queryForObject("""
				insert into cards (recording_id, user_id, kind, summary, start_ms, end_ms, recorded_at)
				values (?, ?, '웃김', '요약', 0, 1000, ?) returning id
				""", Long.class, recordingId, userId, T0);
	}

	// 글 카드 한 장을 만든 시각과 함께 넣는다.
	private long textCard(long userId, long textId, OffsetDateTime createdAt) {
		return jdbc.queryForObject("""
				insert into cards (text_id, user_id, kind, summary, recorded_at, created_at)
				values (?, ?, '웃김', '요약', ?, ?) returning id
				""", Long.class, textId, userId, T0, createdAt);
	}

	private long recording(long userId) {
		return jdbc.queryForObject("""
				insert into recordings (user_id, client_recording_id, started_at) values (?, gen_random_uuid()::text, ?) returning id
				""", Long.class, userId, T0);
	}

	private long writtenText(long userId) {
		return jdbc.queryForObject("insert into texts (user_id, source, title) values (?, 'WRITTEN', '제목') returning id",
				Long.class, userId);
	}

	private long blogText(long userId) {
		long postId = jdbc.queryForObject("""
				insert into blog_posts (user_id, guid, title, link) values (?, gen_random_uuid()::text, '글', 'https://a.b/1') returning id
				""", Long.class, userId);
		return jdbc.queryForObject("insert into texts (user_id, source, blog_post_id, title) values (?, 'BLOG', ?, '제목') returning id",
				Long.class, userId, postId);
	}

	private void speaker(long cardId, String key, String characterType, String characterId) {
		jdbc.update("insert into card_speakers (card_id, speaker_key, character_type, character_id) values (?, ?, ?, ?)",
				cardId, key, characterType, characterId);
	}

	private void line(long cardId, int lineNo, String speakerKey) {
		jdbc.update("insert into card_lines (card_id, line_no, speaker_key, text) values (?, ?, ?, '대사')", cardId, lineNo,
				speakerKey);
	}

	private void setCounts(long cardId, int comicCount, int videoCount) {
		jdbc.update("update cards set comic_count = ?, video_count = ? where id = ?", comicCount, videoCount, cardId);
	}

	private int comicCount(long cardId) {
		return jdbc.queryForObject("select comic_count from cards where id = ?", Integer.class, cardId);
	}

	private int videoCount(long cardId) {
		return jdbc.queryForObject("select video_count from cards where id = ?", Integer.class, cardId);
	}

	private String speakerCharacterId(long cardId, String key) {
		return jdbc.queryForObject("select character_id from card_speakers where card_id = ? and speaker_key = ?",
				String.class, cardId, key);
	}

	private static List<Long> ids(List<Card> cards) {
		return cards.stream().map(Card::getId).toList();
	}

	@Test
	@DisplayName("findPage는 before가 없으면 내 카드를 최신순으로 limit개 읽는다")
	void findPage_before가_없으면_내_카드를_최신순으로_읽는다() {
		// given
		long a = card(1L, null);
		long b = card(1L, null);
		long c = card(1L, null);
		card(2L, null);

		// when & then
		assertThat(ids(cardRepository.findPage(1L, null, Limit.of(10)))).containsExactly(c, b, a);
		assertThat(ids(cardRepository.findPage(1L, null, Limit.of(2)))).containsExactly(c, b);
	}

	@Test
	@DisplayName("findPage는 before와 같은 ID를 빼고 더 작은 ID만 읽는다")
	void findPage_before보다_작은_ID만_읽는다() {
		// given
		long a = card(1L, null);
		long b = card(1L, null);
		long c = card(1L, null);

		// when & then
		assertThat(ids(cardRepository.findPage(1L, c, Limit.of(10)))).containsExactly(b, a);
	}

	@Test
	@DisplayName("findPageOldest는 after보다 큰 내 카드를 오래된순으로 읽는다")
	void findPageOldest_after보다_큰_ID를_오래된순으로_읽는다() {
		// given
		long a = card(1L, null);
		long b = card(1L, null);
		long c = card(1L, null);
		card(2L, null);

		// when & then
		assertThat(ids(cardRepository.findPageOldest(1L, null, Limit.of(10)))).containsExactly(a, b, c);
		assertThat(ids(cardRepository.findPageOldest(1L, a, Limit.of(10)))).containsExactly(b, c);
		assertThat(ids(cardRepository.findPageOldest(1L, a, Limit.of(1)))).containsExactly(b);
	}

	@Test
	@DisplayName("incrementComicCount는 2번 만든 카드를 3으로 늘린다")
	void incrementComicCount_2면_3으로_늘린다() {
		// given
		long id = card(1L, null);
		setCounts(id, 2, 0);

		// when & then
		assertThat(cardRepository.incrementComicCount(id)).isEqualTo(1);
		assertThat(comicCount(id)).isEqualTo(3);
	}

	@Test
	@DisplayName("incrementComicCount는 이미 3번 만든 카드면 0을 돌려주고 늘리지 않는다")
	void incrementComicCount_3이면_늘리지_않는다() {
		// given
		long id = card(1L, null);
		setCounts(id, 3, 0);

		// when & then
		assertThat(cardRepository.incrementComicCount(id)).isZero();
		assertThat(comicCount(id)).isEqualTo(3);
	}

	@Test
	@DisplayName("decrementComicCount는 1을 0으로 줄이고, 0이면 줄이지 않는다")
	void decrementComicCount_0_아래로_내려가지_않는다() {
		// given
		long id = card(1L, null);
		setCounts(id, 1, 0);

		// when & then
		assertThat(cardRepository.decrementComicCount(id)).isEqualTo(1);
		assertThat(comicCount(id)).isZero();
		assertThat(cardRepository.decrementComicCount(id)).isZero();
		assertThat(comicCount(id)).isZero();
	}

	@Test
	@DisplayName("incrementVideoCount는 1을 2로 늘리고, 2면 0을 돌려주고 늘리지 않는다")
	void incrementVideoCount_2를_넘지_않는다() {
		// given
		long id = card(1L, null);
		setCounts(id, 0, 1);

		// when & then
		assertThat(cardRepository.incrementVideoCount(id)).isEqualTo(1);
		assertThat(videoCount(id)).isEqualTo(2);
		assertThat(cardRepository.incrementVideoCount(id)).isZero();
		assertThat(videoCount(id)).isEqualTo(2);
	}

	@Test
	@DisplayName("decrementVideoCount는 1을 0으로 줄이고, 0이면 줄이지 않는다")
	void decrementVideoCount_0_아래로_내려가지_않는다() {
		// given
		long id = card(1L, null);
		setCounts(id, 0, 1);

		// when & then
		assertThat(cardRepository.decrementVideoCount(id)).isEqualTo(1);
		assertThat(videoCount(id)).isZero();
		assertThat(cardRepository.decrementVideoCount(id)).isZero();
		assertThat(videoCount(id)).isZero();
	}

	@Test
	@DisplayName("existsBlogCard는 내 카드 중 블로그 글로 만든 카드만 센다")
	void existsBlogCard_블로그_글_카드만_센다() {
		// given
		textCard(1L, writtenText(1L), T0);
		card(1L, null);
		textCard(2L, blogText(2L), T0);

		// when & then
		assertThat(cardRepository.existsBlogCard(1L)).isFalse();

		// given
		textCard(1L, blogText(1L), T0);

		// when & then
		assertThat(cardRepository.existsBlogCard(1L)).isTrue();
	}

	@Test
	@DisplayName("existsBlogCardSince는 since와 같은 시각에 만든 카드는 세지 않고 그 뒤 것만 센다")
	void existsBlogCardSince_since_뒤에_만든_카드만_센다() {
		// given
		textCard(1L, blogText(1L), T0);
		textCard(1L, writtenText(1L), T0.plusSeconds(10));

		// when & then
		assertThat(cardRepository.existsBlogCardSince(1L, T0)).isFalse();
		assertThat(cardRepository.existsBlogCardSince(1L, T0.minusNanos(1000))).isTrue();
	}

	@Test
	@DisplayName("findIdsWithoutAudio는 그 녹음의 구간 음성이 없는 카드 ID를 id순으로 돌려준다")
	void findIdsWithoutAudio_구간_음성이_없는_카드만_돌려준다() {
		// given
		long recordingId = recording(1L);
		long other = recording(1L);
		long a = card(1L, recordingId);
		long withAudio = card(1L, recordingId);
		long b = card(1L, recordingId);
		card(1L, other);
		jdbc.update("update cards set audio_key = 'k', audio_duration_ms = 100 where id = ?", withAudio);

		// when & then
		assertThat(cardRepository.findIdsWithoutAudio(recordingId)).containsExactly(a, b);
	}

	@Test
	@DisplayName("attachAudio는 구간 음성이 없을 때만 붙이고, 이미 있으면 0을 돌려주고 그대로 둔다")
	void attachAudio_구간_음성이_없을_때만_붙인다() {
		// given
		long id = card(1L, null);

		// when & then
		assertThat(cardRepository.attachAudio(id, "first", 500L)).isEqualTo(1);
		assertThat(cardRepository.attachAudio(id, "second", 900L)).isZero();

		// when & then
		Map<String, Object> row = jdbc.queryForMap("select audio_key, audio_duration_ms from cards where id = ?", id);
		assertThat(row.get("audio_key")).isEqualTo("first");
		assertThat(row.get("audio_duration_ms")).isEqualTo(500L);
	}

	@Test
	@DisplayName("detachProfileCharacter는 그 프로필 캐릭터를 쓴 화자만 떼고 같은 ID의 기본 화자는 그대로 둔다")
	void detachProfileCharacter_프로필_화자만_뗀다() {
		// given
		long c1 = card(1L, null);
		long c2 = card(2L, null);
		speaker(c1, "A", "PROFILE", "7");
		speaker(c2, "A", "PROFILE", "7");
		speaker(c1, "B", "DEFAULT", "7");
		speaker(c2, "B", "PROFILE", "8");

		// when & then
		assertThat(cardRepository.detachProfileCharacter("7")).isEqualTo(2);

		// when & then
		assertThat(speakerCharacterId(c1, "A")).isNull();
		assertThat(speakerCharacterId(c2, "A")).isNull();
		assertThat(speakerCharacterId(c1, "B")).isEqualTo("7");
		assertThat(speakerCharacterId(c2, "B")).isEqualTo("8");
	}

	@Test
	@DisplayName("detachProfileCharactersBetween은 두 사람이 서로의 카드에 쓴 프로필 화자만 뗀다")
	void detachProfileCharactersBetween_서로의_카드에서만_뗀다() {
		// given
		long cardOfA = card(1L, null);
		long cardOfB = card(2L, null);
		long cardOfC = card(3L, null);
		speaker(cardOfA, "A", "PROFILE", "2");
		speaker(cardOfA, "B", "PROFILE", "3");
		speaker(cardOfB, "A", "PROFILE", "1");
		speaker(cardOfC, "A", "PROFILE", "2");
		speaker(cardOfC, "B", "PROFILE", "1");

		// when & then
		assertThat(cardRepository.detachProfileCharactersBetween(1L, "1", 2L, "2")).isEqualTo(2);

		// when & then
		assertThat(speakerCharacterId(cardOfA, "A")).isNull();
		assertThat(speakerCharacterId(cardOfB, "A")).isNull();
		assertThat(speakerCharacterId(cardOfA, "B")).isEqualTo("3");
		assertThat(speakerCharacterId(cardOfC, "A")).isEqualTo("2");
		assertThat(speakerCharacterId(cardOfC, "B")).isEqualTo("1");
	}

	@Test
	@DisplayName("countLinesByCardIds는 카드마다 대사 수를 세고 대사 없는 카드는 뺀다")
	void countLinesByCardIds_카드마다_대사_수를_센다() {
		// given
		long a = card(1L, null);
		long b = card(1L, null);
		long empty = card(1L, null);
		line(a, 1, "A");
		line(a, 2, "B");
		line(b, 1, "A");

		// when
		Map<Long, Long> counts = cardRepository.countLinesByCardIds(List.of(a, b, empty)).stream()
				.collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));

		// then
		assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(a, 2L, b, 1L));
	}

	@Test
	@DisplayName("findSpeakerKeys는 그 카드의 화자 key만 돌려준다")
	void findSpeakerKeys_그_카드의_화자만_돌려준다() {
		// given
		long a = card(1L, null);
		long b = card(1L, null);
		speaker(a, "A", null, null);
		speaker(a, "B", null, null);
		speaker(b, "C", null, null);

		// when & then
		assertThat(cardRepository.findSpeakerKeys(a)).containsExactlyInAnyOrder("A", "B");
	}

	@Test
	@DisplayName("renameSpeaker와 renameLineSpeaker는 그 카드의 그 화자만 바꾼다")
	void renameSpeaker_그_카드의_그_화자만_바꾼다() {
		// given
		long a = card(1L, null);
		long b = card(1L, null);
		speaker(a, "A", null, null);
		speaker(b, "A", null, null);
		line(a, 1, "A");
		line(a, 2, "B");
		line(b, 1, "A");

		// when & then
		assertThat(cardRepository.renameSpeaker(a, "A", "Z")).isEqualTo(1);
		assertThat(cardRepository.renameLineSpeaker(a, "A", "Z")).isEqualTo(1);

		// when & then
		assertThat(cardRepository.findSpeakerKeys(a)).containsExactly("Z");
		assertThat(cardRepository.findSpeakerKeys(b)).containsExactly("A");
		assertThat(jdbc.queryForList("select speaker_key from card_lines where card_id = ? order by line_no", String.class, a))
				.containsExactly("Z", "B");
		assertThat(jdbc.queryForList("select speaker_key from card_lines where card_id = ?", String.class, b))
				.containsExactly("A");
	}

	@Test
	@DisplayName("deleteAllByUserId는 그 회원의 카드와 화자·대사를 지우고 다른 회원 카드는 둔다")
	void deleteAllByUserId_그_회원의_카드만_지운다() {
		// given
		long mine = card(1L, null);
		long other = card(2L, null);
		speaker(mine, "A", null, null);
		line(mine, 1, "A");
		speaker(other, "A", null, null);

		// when & then
		assertThat(cardRepository.deleteAllByUserId(1L)).isEqualTo(1);

		// when & then
		assertThat(jdbc.queryForList("select id from cards where user_id in (1, 2)", Long.class)).containsExactly(other);
		assertThat(jdbc.queryForObject("select count(*) from card_speakers where card_id = ?", Long.class, mine)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from card_lines where card_id = ?", Long.class, mine)).isZero();
	}

}
