package com.lolog.api.domain.text.repository;

import com.lolog.api.domain.text.entity.Text;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 글 DB 조회.
public interface TextRepository extends JpaRepository<Text, Long> {

	// 워커가 같은 글의 카드를 동시에 보내도 하나씩 받게 글 행을 잠근다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Text t where t.id = :id")
	Optional<Text> findByIdForUpdate(@Param("id") Long id);

	// 내 글 하나를 찾는다.
	Optional<Text> findByIdAndUserId(Long id, Long userId);

	// 가져온 블로그 글들로 만든 글.
	List<Text> findByBlogPostIdIn(Collection<Long> blogPostIds);

}
