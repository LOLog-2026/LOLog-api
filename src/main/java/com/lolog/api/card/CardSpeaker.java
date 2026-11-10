package com.lolog.api.card;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "card_speakers")
@Getter
@NoArgsConstructor
public class CardSpeaker {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "card_id", nullable = false)
	private Card card;

	@Column(name = "speaker_key", nullable = false, length = 20)
	private String speakerKey;

	// 사용자가 붙인 이름. 카드가 생길 때는 null이다.
	@Setter
	@Column(length = 50)
	private String name;

	// 붙인 그림(characters.md). DEFAULT(기본 화자 id) 또는 PROFILE(회원 ID를 글자로). 안 붙였으면 둘 다 null이다.
	@Column(name = "character_type", length = 10)
	private String characterType;

	@Column(name = "character_id", length = 20)
	private String characterId;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	public void setCharacter(String type, String id) {
		this.characterType = type;
		this.characterId = type == null ? null : id;
	}

	CardSpeaker(Card card, String speakerKey) {
		this.card = card;
		this.speakerKey = speakerKey;
	}

}
