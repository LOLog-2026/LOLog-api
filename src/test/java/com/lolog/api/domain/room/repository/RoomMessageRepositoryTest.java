package com.lolog.api.domain.room.repository;

import static com.lolog.api.support.RepositoryRows.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.support.RepositoryRows;
import com.lolog.api.support.RepositoryTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("RoomMessageRepository")
class RoomMessageRepositoryTest extends RepositoryTest {

	private static final long ME = 1L;
	private static final long OTHER = 2L;

	@Autowired
	private RoomMessageRepository messages;

	@Autowired
	private JdbcTemplate jdbc;

	private RepositoryRows rows;

	private long room;

	@BeforeEach
	void setUp() {
		rows = new RepositoryRows(jdbc);
		room = rows.room("방", ME, at(0), null);
	}

	@Test
	@DisplayName("대화 줄을 넣으면 새 ID를 돌려주고 시각이 채워진다")
	void 대화_줄을_넣는다() {
		// when
		Long id = messages.insert(room, ME, "COMMENT", null, "안녕");

		// then
		assertThat(messages.findView(id)).hasValueSatisfying(v -> {
			assertThat(v.userId()).isEqualTo(ME);
			assertThat(v.type()).isEqualTo("COMMENT");
			assertThat(v.body()).isEqualTo("안녕");
			assertThat(v.createdAt()).isNotNull();
			assertThat(v.comicId()).isNull();
		});
	}

	@Test
	@DisplayName("대화 한 쪽은 ID 내림차순이고, before를 주면 그보다 작은 ID만 읽는다")
	void 대화_한_쪽은_ID_내림차순이다() {
		// given
		long m1 = rows.message(room, ME, "CREATED", null, null, at(0));
		long m2 = rows.message(room, OTHER, "COMMENT", null, "둘", at(1));
		long m3 = rows.message(room, ME, "COMMENT", null, "셋", at(2));
		long m4 = rows.message(room, OTHER, "COMMENT", null, "넷", at(3));
		long otherRoom = rows.room("다른방", ME, at(0), null);
		rows.message(otherRoom, ME, "CREATED", null, null, at(4));

		// when & then
		assertThat(messages.findPage(room, null, Limit.of(3))).extracting(RoomMessageView::id)
			.containsExactly(m4, m3, m2);
		// 커서와 같은 ID는 빼고 그보다 작은 것만
		assertThat(messages.findPage(room, m2, Limit.of(3))).extracting(RoomMessageView::id).containsExactly(m1);
		assertThat(messages.findPage(room, m1, Limit.of(3))).isEmpty();
	}

	@Test
	@DisplayName("만화에 단 줄이면 그 만화의 ID·제목·그림을 담는다")
	void 만화에_단_줄이면_만화_정보를_담는다() {
		// given
		long comic = rows.comic(ME, "만화 제목", "comics/9.png");
		long post = rows.post(room, comic, ME, at(0));
		long posted = rows.message(room, ME, "POSTED", post, null, at(1));
		long created = rows.message(room, ME, "CREATED", null, null, at(0));

		// when
		List<RoomMessageView> page = messages.findPage(room, null, Limit.of(10));

		// then
		assertThat(page).filteredOn(v -> v.id() == posted).singleElement().satisfies(v -> {
			assertThat(v.postId()).isEqualTo(post);
			assertThat(v.comicId()).isEqualTo(comic);
			assertThat(v.postTitle()).isEqualTo("만화 제목");
			assertThat(v.postImageKey()).isEqualTo("comics/9.png");
		});
		assertThat(page).filteredOn(v -> v.id() == created).singleElement().satisfies(v -> {
			assertThat(v.comicId()).isNull();
			assertThat(v.postTitle()).isNull();
		});
	}

	@Test
	@DisplayName("없는 대화 줄은 빈 값이다")
	void 없는_대화_줄은_빈_값이다() {
		// when & then
		assertThat(messages.findView(999_999L)).isEmpty();
	}

	@Test
	@DisplayName("좋아요 줄을 뺄 때 그 회원·그 만화의 LIKED 줄만 지운다")
	void 그_회원의_LIKED_줄만_지운다() {
		// given
		long post = rows.post(room, rows.comic(ME, "가", null), ME, at(0));
		long otherPost = rows.post(room, rows.comic(ME, "나", null), ME, at(0));
		rows.message(room, ME, "LIKED", post, null, at(1));
		long othersLike = rows.message(room, OTHER, "LIKED", post, null, at(1));
		long myOtherLike = rows.message(room, ME, "LIKED", otherPost, null, at(1));
		long myComment = rows.message(room, ME, "COMMENT", post, "댓글", at(1));

		// when & then
		assertThat(messages.deleteLiked(post, ME)).isEqualTo(1);
		assertThat(messages.findAll()).extracting(m -> m.getId())
			.containsExactlyInAnyOrder(othersLike, myOtherLike, myComment);
	}

	@Test
	@DisplayName("탈퇴하면 그 회원이 남긴 대화 줄만 지운다")
	void 탈퇴하면_그_회원의_대화_줄만_지운다() {
		// given
		rows.message(room, ME, "CREATED", null, null, at(0));
		rows.message(room, ME, "COMMENT", null, "내 글", at(1));
		long others = rows.message(room, OTHER, "COMMENT", null, "남의 글", at(2));

		// when & then
		assertThat(messages.deleteAllByUserId(ME)).isEqualTo(2);
		assertThat(messages.findAll()).extracting(m -> m.getId()).containsExactly(others);
	}

}
