package com.lolog.api.comic;

import com.lolog.api.comic.ComicDtos.ComicPage;
import com.lolog.api.comic.ComicDtos.ComicResponse;
import com.lolog.api.common.ApiException;
import com.lolog.api.common.PageRequestParams;
import com.lolog.api.common.PageSort;
import com.lolog.api.storage.ImageStorage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 앱의 만화 목록·자세히·지우기(FR-LIB-01, FR-COMIC-03, FR-LIB-03).
@Service
public class ComicQueryService {

	private static final Logger log = LoggerFactory.getLogger(ComicQueryService.class);

	private final ComicRepository comics;
	private final ImageStorage storage;
	private final TransactionTemplate tx;

	public ComicQueryService(ComicRepository comics, ImageStorage storage, TransactionTemplate tx) {
		this.comics = comics;
		this.storage = storage;
		this.tx = tx;
	}

	public ComicPage list(long userId, String cursor, Integer size, String sort) {
		PageSort order = PageSort.of(sort);
		PageRequestParams params = PageRequestParams.of(cursor, size);
		List<Comic> page = order == PageSort.OLDEST
				? comics.findPageOldest(userId, params.before(), Limit.of(params.size() + 1))
				: comics.findPage(userId, params.before(), Limit.of(params.size() + 1));
		boolean more = page.size() > params.size();
		List<Comic> shown = more ? page.subList(0, params.size()) : page;
		return new ComicPage(shown.stream().map(this::toResponse).toList(),
				more ? String.valueOf(shown.getLast().getId()) : null);
	}

	public ComicResponse detail(long userId, long id) {
		return toResponse(comics.findByIdAndUserId(id, userId).orElseThrow(ComicQueryService::notFound));
	}

	// 만드는 중인 만화도 지울 수 있다. 작업(jobs)은 DB가 함께 지우고(ON DELETE CASCADE), 워커의 다음 요청은 404가 된다.
	// 만들기 횟수(comic_count)는 그대로다(지운 만화도 셈). LOLog-docs PR #27
	public void delete(long userId, long id) {
		Comic comic = tx.execute(status -> {
			Comic found = comics.findByIdAndUserIdForUpdate(id, userId).orElseThrow(ComicQueryService::notFound);
			comics.delete(found);
			return found;
		});
		// DB에서 지운 뒤 이미지를 지운다. image_key가 없어도 올라가 있을 수 있어(#6에서 올린 뒤 실패) 정해진 키로 지운다.
		try {
			storage.delete(ImageStorage.comicKey(comic.getUserId(), comic.getId()));
		}
		catch (RuntimeException e) {
			log.error("만화 이미지를 지우지 못했습니다. comicId={}", comic.getId(), e);
		}
	}

	private ComicResponse toResponse(Comic comic) {
		String url = comic.getStatus() == ComicStatus.DONE && comic.getImageKey() != null
				? storage.presignedUrl(comic.getImageKey()).toString() : null;
		// V2 전에 만든 만화는 card_summary가 NULL이라 빈 글자로 준다(명세상 필수, 영상·방과 같음).
		String summary = comic.getCardSummary() == null ? "" : comic.getCardSummary();
		return new ComicResponse(comic.getId(), comic.getCardId(), summary, comic.getStatus(), url,
				comic.getCreatedAt());
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "COMIC_NOT_FOUND", "만화를 찾을 수 없습니다.");
	}

}
