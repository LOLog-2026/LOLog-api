package com.lolog.api.domain.video.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.video.entity.Video;
import com.lolog.api.support.RepositoryTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("VideoRepository")
class VideoRepositoryTest extends RepositoryTest {

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private JdbcTemplate jdbc;

	private long video(long userId) {
		return jdbc.queryForObject("insert into videos (user_id, status) values (?, 'DONE') returning id", Long.class, userId);
	}

	private static List<Long> ids(List<Video> videos) {
		return videos.stream().map(Video::getId).toList();
	}

	@Test
	@DisplayName("findPage는 내 영상을 최신순으로 읽고 before보다 작은 ID만 읽는다")
	void findPage_최신순으로_커서_앞만_읽는다() {
		// given
		long a = video(1L);
		long b = video(1L);
		long c = video(1L);
		video(2L);

		// when & then
		assertThat(ids(videoRepository.findPage(1L, null, Limit.of(10)))).containsExactly(c, b, a);
		assertThat(ids(videoRepository.findPage(1L, c, Limit.of(10)))).containsExactly(b, a);
		assertThat(ids(videoRepository.findPage(1L, null, Limit.of(2)))).containsExactly(c, b);
	}

	@Test
	@DisplayName("findPageOldest는 내 영상을 오래된순으로 읽고 after보다 큰 ID만 읽는다")
	void findPageOldest_오래된순으로_커서_뒤만_읽는다() {
		// given
		long a = video(1L);
		long b = video(1L);
		long c = video(1L);
		video(2L);

		// when & then
		assertThat(ids(videoRepository.findPageOldest(1L, null, Limit.of(10)))).containsExactly(a, b, c);
		assertThat(ids(videoRepository.findPageOldest(1L, a, Limit.of(10)))).containsExactly(b, c);
	}

	@Test
	@DisplayName("deleteAllByUserId는 그 회원의 영상만 지운다")
	void deleteAllByUserId_그_회원의_영상만_지운다() {
		// given
		video(1L);
		video(1L);
		long other = video(2L);

		// when
		int deleted = videoRepository.deleteAllByUserId(1L);

		// then
		assertThat(deleted).isEqualTo(2);
		assertThat(jdbc.queryForList("select id from videos where user_id in (1, 2)", Long.class)).containsExactly(other);
	}

}
