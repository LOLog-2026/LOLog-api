package com.lolog.api.domain.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 방에 올린 만화 한 줄(V8 room_posts). 같은 만화는 한 방에 한 번이다(uk_room_posts_room_id_comic_id).
// 방이나 만화를 지우면 DB가 함께 지운다. 넣기는 이미 올렸으면 넣지 않는 on conflict 문장이라 리포지토리의 native query로 만든다.
@Entity
@Table(name = "room_posts")
@Getter
@NoArgsConstructor
public class RoomPost {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "room_id", nullable = false)
	private Long roomId;

	@Column(name = "comic_id", nullable = false)
	private Long comicId;

	// 올린 회원
	@Column(name = "user_id", nullable = false)
	private Long userId;

	// 올린 시각
	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

}
