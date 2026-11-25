package com.lolog.api.domain.room.repository;

import static com.lolog.api.support.RepositoryRows.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.support.RepositoryRows;
import com.lolog.api.support.RepositoryTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("RoomPostRepository")
class RoomPostRepositoryTest extends RepositoryTest {

	private static final long ME = 1L;
	private static final long OTHER = 2L;

	@Autowired
	private RoomPostRepository posts;

	@Autowired
	private JdbcTemplate jdbc;

	private RepositoryRows rows;

	@BeforeEach
	void setUp() {
		rows = new RepositoryRows(jdbc);
	}

	@Test
	@DisplayName("같은 방에 같은 만화를 다시 올리면 넣지 않고 빈 값이다")
	void 같은_만화를_다시_올리면_빈_값이다() {
		// given
		long room = rows.room("방", ME, at(0), null);
		long otherRoom = rows.room("다른방", ME, at(0), null);
		long comic = rows.comic(ME, "제목", "comics/1.png");

		// when
		Optional<Long> first = posts.insertNew(room, comic, ME);
		Optional<Long> again = posts.insertNew(room, comic, OTHER);
		Optional<Long> elsewhere = posts.insertNew(otherRoom, comic, ME);

		// then
		assertThat(first).isPresent();
		assertThat(again).isEmpty();
		assertThat(elsewhere).isPresent().isNotEqualTo(first);
		assertThat(posts.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("다른 방의 올린 만화는 방 ID로 찾지 않는다")
	void 다른_방의_올린_만화는_찾지_않는다() {
		// given
		long room = rows.room("방", ME, at(0), null);
		long otherRoom = rows.room("다른방", ME, at(0), null);
		long post = rows.post(room, rows.comic(ME, "제목", null), ME, at(0));

		// when & then
		assertThat(posts.findByIdAndRoomId(post, room)).isPresent();
		assertThat(posts.findByIdAndRoomId(post, otherRoom)).isEmpty();
	}

	@Test
	@DisplayName("올린 만화는 최근에 올린 것이 앞이고 좋아요 수·내가 눌렀는지·댓글 수를 담는다")
	void 올린_만화_목록을_읽는다() {
		// given
		long room = rows.room("방", ME, at(0), null);
		long otherRoom = rows.room("다른방", ME, at(0), null);
		long older = rows.post(room, rows.comic(OTHER, "옛 만화", "comics/old.png"), OTHER, at(1));
		long newer = rows.post(room, rows.comic(ME, "새 만화", "comics/new.png"), ME, at(2));
		rows.post(otherRoom, rows.comic(ME, "다른 방 만화", null), ME, at(3));
		rows.like(newer, ME);
		rows.like(newer, OTHER);
		rows.like(older, OTHER);
		rows.message(room, ME, "COMMENT", older, "댓글1", at(4));
		rows.message(room, OTHER, "COMMENT", older, "댓글2", at(5));
		// 좋아요 줄은 댓글로 세지 않는다
		rows.message(room, OTHER, "LIKED", older, null, at(6));

		// when
		List<RoomPostView> views = posts.findViews(ME, room, null);

		// then
		assertThat(views).extracting(RoomPostView::id).containsExactly(newer, older);
		assertThat(views.get(0)).satisfies(v -> {
			assertThat(v.title()).isEqualTo("새 만화");
			assertThat(v.imageKey()).isEqualTo("comics/new.png");
			assertThat(v.likes()).isEqualTo(2);
			assertThat(v.liked()).isTrue();
			assertThat(v.comments()).isZero();
		});
		assertThat(views.get(1)).satisfies(v -> {
			assertThat(v.likes()).isEqualTo(1);
			assertThat(v.liked()).isFalse();
			assertThat(v.comments()).isEqualTo(2);
		});
	}

	@Test
	@DisplayName("올린 시각이 같으면 ID가 큰 것이 앞이다")
	void 같은_시각이면_ID가_큰_것이_앞이다() {
		// given
		long room = rows.room("방", ME, at(0), null);
		long first = rows.post(room, rows.comic(ME, "가", null), ME, at(1));
		long second = rows.post(room, rows.comic(ME, "나", null), ME, at(1));

		// when & then
		assertThat(posts.findViews(ME, room, null)).extracting(RoomPostView::id).containsExactly(second, first);
	}

	@Test
	@DisplayName("postId를 주면 그 하나만 읽고, 다른 방의 것이면 비어 있다")
	void postId를_주면_그_하나만_읽는다() {
		// given
		long room = rows.room("방", ME, at(0), null);
		long otherRoom = rows.room("다른방", ME, at(0), null);
		long target = rows.post(room, rows.comic(ME, "가", null), ME, at(1));
		rows.post(room, rows.comic(ME, "나", null), ME, at(2));

		// when & then
		assertThat(posts.findViews(ME, room, target)).extracting(RoomPostView::id).containsExactly(target);
		assertThat(posts.findViews(ME, otherRoom, target)).isEmpty();
	}

	@Test
	@DisplayName("올린 만화를 내리면 좋아요와 대화 줄도 함께 지워진다")
	void 올린_만화를_내리면_좋아요와_대화도_지워진다() {
		// given
		long room = rows.room("방", ME, at(0), null);
		long post = rows.post(room, rows.comic(ME, "가", null), ME, at(1));
		rows.like(post, OTHER);
		rows.message(room, OTHER, "COMMENT", post, "댓글", at(2));
		rows.message(room, ME, "CREATED", null, null, at(0));

		// when & then
		assertThat(posts.deletePost(post)).isEqualTo(1);
		assertThat(posts.deletePost(post)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from room_post_likes", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from room_messages where room_id = ?", Long.class, room))
			.isEqualTo(1);
	}

}
