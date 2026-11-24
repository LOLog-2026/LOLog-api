package com.lolog.api.domain.blog.repository;

import com.lolog.api.domain.blog.entity.BlogPost;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 가져온 블로그 글 DB 조회.
public interface BlogPostRepository extends JpaRepository<BlogPost, Long> {

	// 옛 글을 guid만 적어 둔다(skipped). 이미 있으면 그대로 둔다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			insert into blog_posts (source_id, user_id, guid, skipped) values (:sourceId, :userId, :guid, true)
			on conflict (user_id, guid) do nothing
			""", nativeQuery = true)
	void insertSkipped(@Param("sourceId") Long sourceId, @Param("userId") Long userId, @Param("guid") String guid);

	// 처음 보는 글이면 넣고 ID를 돌려준다. 이미 있으면 비어 있다(주기적으로 읽을 때).
	@Query(value = """
			insert into blog_posts (source_id, user_id, guid, title, link, category, published_at, blog_title)
			values (:sourceId, :userId, :guid, :title, :link, :category, :publishedAt, :blogTitle)
			on conflict (user_id, guid) do nothing
			returning id
			""", nativeQuery = true)
	Optional<Long> insertNew(@Param("sourceId") Long sourceId, @Param("userId") Long userId, @Param("guid") String guid,
			@Param("title") String title, @Param("link") String link, @Param("category") String category,
			@Param("publishedAt") OffsetDateTime publishedAt, @Param("blogTitle") String blogTitle);

	// 처음 보는 글이면 넣고, 옛 글로 적어 둔 글(skipped)이면 이번에 가져온다. 넣거나 되살린 글의 ID, 이미 가져온 글이면 비어 있다
	// (연결·카테고리 추가 때).
	@Query(value = """
			insert into blog_posts (source_id, user_id, guid, title, link, category, published_at, blog_title)
			values (:sourceId, :userId, :guid, :title, :link, :category, :publishedAt, :blogTitle)
			on conflict (user_id, guid) do update set source_id = excluded.source_id, title = excluded.title,
			    link = excluded.link, category = excluded.category, published_at = excluded.published_at,
			    blog_title = excluded.blog_title, skipped = false, created_at = current_timestamp
			where blog_posts.skipped
			returning id
			""", nativeQuery = true)
	Optional<Long> insertOrRevive(@Param("sourceId") Long sourceId, @Param("userId") Long userId,
			@Param("guid") String guid, @Param("title") String title, @Param("link") String link,
			@Param("category") String category, @Param("publishedAt") OffsetDateTime publishedAt,
			@Param("blogTitle") String blogTitle);

	// 블로그의 가져온 글 목록. 최신순(id 내림차순), before가 null이면 처음부터. 옛 글(skipped)은 뺀다.
	@Query("select p from BlogPost p where p.sourceId = :sourceId and p.skipped = false and (:before is null or p.id < :before) order by p.id desc")
	List<BlogPost> findPage(@Param("sourceId") Long sourceId, @Param("before") Long before, Limit limit);

}
