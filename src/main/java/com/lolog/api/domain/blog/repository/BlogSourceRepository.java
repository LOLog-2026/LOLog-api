package com.lolog.api.domain.blog.repository;

import com.lolog.api.domain.blog.entity.BlogSource;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 연결한 블로그 DB 조회.
public interface BlogSourceRepository extends JpaRepository<BlogSource, Long> {

	// 같은 피드를 이미 연결했는지 본다.
	boolean existsByUserIdAndFeedUrl(Long userId, String feedUrl);

	// 내 블로그인지 본다.
	boolean existsByIdAndUserId(Long id, Long userId);

	// 내 블로그 하나를 찾는다.
	Optional<BlogSource> findByIdAndUserId(Long id, Long userId);

	// 내 블로그 목록(연결한 순서).
	List<BlogSource> findAllByUserIdOrderById(Long userId);

	// 내 블로그 행을 잠그고 찾는다. 카테고리 바꾸기와 끊기가 엇갈리지 않게 한다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from BlogSource s where s.id = :id and s.userId = :userId")
	Optional<BlogSource> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	// 블로그 행을 잠그고 찾는다. 주기적으로 읽는 사이 연결을 끊었는지 본다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from BlogSource s where s.id = :id")
	Optional<BlogSource> findByIdForUpdate(@Param("id") Long id);

	// 모든 블로그의 ID(주기적으로 읽을 때).
	@Query("select s.id from BlogSource s order by s.id")
	List<Long> findAllIds();

	// 회원의 블로그 연결을 모두 지운다(탈퇴).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from BlogSource s where s.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
