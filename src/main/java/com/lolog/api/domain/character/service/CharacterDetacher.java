package com.lolog.api.domain.character.service;

import com.lolog.api.domain.card.repository.CardRepository;
import org.springframework.stereotype.Component;

// 카드 화자에 붙은 프로필 캐릭터를 뺀다(characters.md 3장, db.md 4장 "캐릭터가 빠질 때").
// 지우기·끊기·차단·탈퇴를 처리하는 그때 바로 빼고, 다시 친구가 되거나 캐릭터를 다시 만들어도 저절로 돌아오지 않는다.
// 이미 만든 만화는 그대로다. character_id는 글자라 회원 ID를 글자로 바꿔 비교한다(character_id::bigint는 기본 화자 id에서 오류).
@Component
public class CharacterDetacher {

	private final CardRepository cards;

	/** 카드 저장소를 받는다. */
	public CharacterDetacher(CardRepository cards) {
		this.cards = cards;
	}

	/**
	 * 캐릭터 지우기·탈퇴: 모든 카드에서 그 사람의 캐릭터를 뺀다.
	 *
	 * @param userId 캐릭터를 뺄 회원 ID(캐릭터를 지우거나 탈퇴한 회원)
	 */
	public void detachEverywhere(long userId) {
		cards.detachProfileCharacter(String.valueOf(userId));
	}

	/**
	 * 친구 끊기·차단: 두 사람이 서로의 카드에서 빠진다. 본인 카드에 붙인 본인 캐릭터는 그대로다.
	 *
	 * @param a 친구를 끊거나 차단한 두 회원 중 한 명의 ID
	 * @param b 다른 한 명의 회원 ID
	 */
	public void detachBetween(long a, long b) {
		cards.detachProfileCharactersBetween(a, String.valueOf(a), b, String.valueOf(b));
	}

}
