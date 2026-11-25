package com.lolog.api.domain.blog.repository;

import com.lolog.api.domain.blog.entity.BlogPost;
import com.lolog.api.domain.text.repository.TextRepository;
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

	/**
	 * 옛 글을 guid만 적어 둔다(skipped). 이미 있으면 그대로 둔다.
	 * 새 줄만 넣어 이미 읽은 엔티티가 낡지 않으므로 영속성 컨텍스트를 비우지 않는다(뒤에 바꾼 엔티티가 저장되게).
	 *
	 * @param sourceId 글을 가져온 블로그 ID
	 * @param userId 블로그 주인 회원 ID
	 * @param guid 피드의 글 구분 값
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			insert into blog_posts (source_id, user_id, guid, skipped) values (:sourceId, :userId, :guid, true)
			on conflict (user_id, guid) do nothing
			""", nativeQuery = true)
	void insertSkipped(@Param("sourceId") Long sourceId, @Param("userId") Long userId, @Param("guid") String guid);

	/**
	 * 처음 보는 글이면 넣고 ID를 돌려준다. 이미 있으면 비어 있다(주기적으로 읽을 때).
	 *
	 * @param sourceId 글을 가져온 블로그 ID
	 * @param userId 블로그 주인 회원 ID
	 * @param guid 피드의 글 구분 값
	 * @param title 글 제목
	 * @param link 글 주소
	 * @param category 맞은 카테고리. 없으면 null
	 * @param publishedAt 글을 쓴 시각. 없으면 null
	 * @param blogTitle 가져올 때의 블로그 이름
	 * @return 넣은 글 ID. 이미 있으면 비어 있음
	 */
	@Query(value = """
			insert into blog_posts (source_id, user_id, guid, title, link, category, published_at, blog_title)
			values (:sourceId, :userId, :guid, :title, :link, :category, :publishedAt, :blogTitle)
			on conflict (user_id, guid) do nothing
			returning id
			""", nativeQuery = true)
	Optional<Long> insertNew(@Param("sourceId") Long sourceId, @Param("userId") Long userId, @Param("guid") String guid,
			@Param("title") String title, @Param("link") String link, @Param("category") String category,
			@Param("publishedAt") OffsetDateTime publishedAt, @Param("blogTitle") String blogTitle);

	/**
	 * 처음 보는 글이면 넣고, 옛 글로 적어 둔 글(skipped)이면 이번에 가져온다. 넣거나 되살린 글의 ID, 이미 가져온 글이면 비어 있다
	 * (연결·카테고리 추가 때).
	 *
	 * @param sourceId 글을 가져온 블로그 ID
	 * @param userId 블로그 주인 회원 ID
	 * @param guid 피드의 글 구분 값
	 * @param title 글 제목
	 * @param link 글 주소
	 * @param category 맞은 카테고리. 없으면 null
	 * @param publishedAt 글을 쓴 시각. 없으면 null
	 * @param blogTitle 가져올 때의 블로그 이름
	 * @return 넣거나 되살린 글 ID. 이미 가져온 글이면 비어 있음
	 */
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

	/**
	 * 블로그의 가져온 글 목록과 그 글로 만든 글의 첫 카드·상태. 최신순(id 내림차순), before가 null이면 처음부터.
	 * 옛 글(skipped)은 뺀다.
	 *
	 * @param sourceId 블로그 ID
	 * @param before 이 ID보다 작은 글만. null이면 처음부터
	 * @param limit 가져올 최대 수
	 * @return 가져온 글 목록 줄
	 */
	@Query("select new com.lolog.api.domain.blog.repository.BlogPostView(p.id, p.title, p.link, p.category, p.publishedAt,"
			+ " t.id, " + TextRepository.FIRST_CARD_JPQL + ", " + TextRepository.STATUS_JPQL + ")"
			+ " from BlogPost p left join Text t on t.blogPostId = p.id"
			+ " where p.sourceId = :sourceId and p.skipped = false and (:before is null or p.id < :before) order by p.id desc")
	List<BlogPostView> findPage(@Param("sourceId") Long sourceId, @Param("before") Long before, Limit limit);

	/**
	 * 회원이 가져온 블로그 글을 모두 지운다(탈퇴).
	 *
	 * @param userId 탈퇴한 회원 ID
	 * @return 지운 행 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from BlogPost p where p.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
