package com.lolog.api.global.storage.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.global.storage.entity.StorageDeletion;
import com.lolog.api.support.RepositoryTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("StorageDeletionRepository")
class StorageDeletionRepositoryTest extends RepositoryTest {

	@Autowired
	private StorageDeletionRepository deletions;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("다시 지울 줄은 덜 실패한 줄부터, 같으면 오래된 줄부터 읽는다")
	void 덜_실패한_줄부터_읽는다() {
		// given
		long failedTwice = insert("a", false, 2);
		long fresh1 = insert("b", false, 0);
		long failedOnce = insert("c", false, 1);
		long fresh2 = insert("d", true, 0);

		// when & then
		assertThat(deletions.findRetryIds(Limit.of(3))).containsExactly(fresh1, fresh2, failedOnce);
		assertThat(deletions.findRetryIds(Limit.of(10))).containsExactly(fresh1, fresh2, failedOnce, failedTwice);
	}

	@Test
	@DisplayName("키로 찾을 때 그 키의 prefix가 아닌 줄만 ID 순으로 읽는다")
	void 키로_찾을_때_prefix_줄은_뺀다() {
		// given
		long second = insert("comics/1.png", false, 0);
		insert("comics/1.png", true, 0);
		insert("comics/2.png", false, 0);
		long third = insert("comics/1.png", false, 3);

		// when & then
		assertThat(deletions.findKeyForUpdate("comics/1.png")).extracting(StorageDeletion::getId)
			.containsExactly(second, third);
	}

	@Test
	@DisplayName("ID로 줄 하나를 읽고, 없는 줄이면 빈 값이다")
	void ID로_줄_하나를_읽는다() {
		// given
		long id = insert("a", false, 0);

		// when & then
		assertThat(deletions.findByIdForUpdateSkipLocked(id)).map(StorageDeletion::getObjectKey).contains("a");
		assertThat(deletions.findByIdForUpdateSkipLocked(id + 1000)).isEmpty();
	}

	private long insert(String key, boolean prefix, int attempts) {
		return jdbc.queryForObject(
				"insert into storage_deletions (object_key, prefix, attempts) values (?, ?, ?) returning id", Long.class,
				key, prefix, attempts);
	}

}
