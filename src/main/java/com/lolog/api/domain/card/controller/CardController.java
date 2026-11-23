package com.lolog.api.domain.card.controller;

import com.lolog.api.domain.card.dto.CardDtos.CardDetail;
import com.lolog.api.domain.card.dto.CardDtos.CardPage;
import com.lolog.api.domain.card.dto.CardDtos.CardUpdateRequest;
import com.lolog.api.domain.card.service.CardService;
import com.lolog.api.global.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cards")
public class CardController {

	private final CardService cardService;
	private final CurrentUserProvider currentUser;

	public CardController(CardService cardService, CurrentUserProvider currentUser) {
		this.cardService = cardService;
		this.currentUser = currentUser;
	}

	@GetMapping
	public CardPage list(@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size,
			@RequestParam(required = false) String sort) {
		return cardService.list(currentUser.currentUserId(), cursor, size, sort);
	}

	@GetMapping("/{id}")
	public CardDetail detail(@PathVariable Long id) {
		return cardService.detail(currentUser.currentUserId(), id);
	}

	@PatchMapping("/{id}")
	public CardDetail update(@PathVariable Long id, @Valid @RequestBody CardUpdateRequest request) {
		return cardService.update(currentUser.currentUserId(), id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		cardService.delete(currentUser.currentUserId(), id);
	}

}
