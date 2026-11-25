package com.lolog.api.domain.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.admin.dto.AdminDtos.AdminUser;
import com.lolog.api.domain.admin.dto.AdminDtos.FeedbackItem;
import com.lolog.api.domain.admin.dto.AdminDtos.FeedbackPage;
import com.lolog.api.domain.admin.dto.AdminDtos.FeedbackRequest;
import com.lolog.api.domain.admin.entity.Feedback;
import com.lolog.api.domain.admin.repository.FeedbackRepository;
import com.lolog.api.domain.inbox.entity.Notice;
import com.lolog.api.domain.inbox.repository.NoticeRepository;
import com.lolog.api.domain.user.entity.UserProfile;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import com.lolog.api.domain.user.repository.UserSuspensionRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminService")
class AdminServiceTest {

	private static final long ME = 5L;
	private static final long NOTICE_ID = 10L;

	@Mock
	private NoticeRepository notices;

	@Mock
	private FeedbackRepository feedback;

	@Mock
	private UserProfileRepository profiles;

	@Mock
	private UserSuspensionRepository suspensions;

	@InjectMocks
	private AdminService service;

	// ---- createNotice ----

	@Test
	@DisplayName("제목이 공백뿐이면 title 칸 INVALID_REQUEST이고 저장하지 않는다")
	void 제목이_비면_INVALID_REQUEST() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.createNotice("  ", "본문"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("title");
		verifyNoInteractions(notices);
	}

	@Test
	@DisplayName("제목이 101자면 title 칸 INVALID_REQUEST다")
	void 제목이_101자면_INVALID_REQUEST() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.createNotice("가".repeat(AdminService.TITLE_MAX + 1), "본문"));

		// then
		assertThat(e.getField()).isEqualTo("title");
	}

	@Test
	@DisplayName("본문이 null이면 body 칸 INVALID_REQUEST다")
	void 본문이_없으면_INVALID_REQUEST() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.createNotice("제목", null));

		// then
		assertThat(e.getField()).isEqualTo("body");
		verifyNoInteractions(notices);
	}

	@Test
	@DisplayName("제목 100자(이모지도 한 글자)와 본문을 다듬어 저장하고 응답 모양으로 돌려준다")
	void 공지사항을_만든다() {
		// given
		String title = "😀".repeat(AdminService.TITLE_MAX);
		given(notices.save(any(Notice.class))).willAnswer(inv -> {
			Notice n = inv.getArgument(0);
			ReflectionTestUtils.setField(n, "id", NOTICE_ID);
			return n;
		});

		// when
		com.lolog.api.domain.admin.dto.AdminDtos.Notice result = service.createNotice("  " + title + "  ", "  본문  ");

		// then
		assertThat(result.id()).isEqualTo(NOTICE_ID);
		assertThat(result.title()).isEqualTo(title);
		assertThat(result.body()).isEqualTo("본문");
	}

	// ---- updateNotice ----

	@Test
	@DisplayName("없는 공지사항은 NOTICE_NOT_FOUND다")
	void 없는_공지사항은_NOTICE_NOT_FOUND() {
		// given
		given(notices.findById(NOTICE_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.updateNotice(NOTICE_ID, "t", "b"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTICE_NOT_FOUND);
		verify(notices, never()).flush();
	}

	@Test
	@DisplayName("둘 다 빼면 고치지 않고 그대로 돌려준다")
	void 둘_다_빼면_그대로() {
		// given
		Notice notice = notice("옛 제목", "옛 본문");
		given(notices.findById(NOTICE_ID)).willReturn(Optional.of(notice));

		// when
		com.lolog.api.domain.admin.dto.AdminDtos.Notice result = service.updateNotice(NOTICE_ID, null, null);

		// then
		assertThat(result.title()).isEqualTo("옛 제목");
		assertThat(result.body()).isEqualTo("옛 본문");
		assertThat(notice.getUpdatedAt()).isNull();
	}

	@Test
	@DisplayName("제목만 보내면 제목만 바꾸고 수정 일시를 반영한 뒤 돌려준다")
	void 제목만_바꾼다() {
		// given
		Notice notice = notice("옛 제목", "옛 본문");
		given(notices.findById(NOTICE_ID)).willReturn(Optional.of(notice));

		// when
		com.lolog.api.domain.admin.dto.AdminDtos.Notice result = service.updateNotice(NOTICE_ID, " 새 제목 ", null);

		// then
		assertThat(result.title()).isEqualTo("새 제목");
		assertThat(result.body()).isEqualTo("옛 본문");
		assertThat(result.updatedAt()).isNotNull();
		verify(notices).flush();
	}

	@Test
	@DisplayName("보낸 본문이 공백뿐이면 body 칸 INVALID_REQUEST이고 고치지 않는다")
	void 빈_본문으로_고치면_INVALID_REQUEST() {
		// given
		Notice notice = notice("옛 제목", "옛 본문");
		given(notices.findById(NOTICE_ID)).willReturn(Optional.of(notice));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.updateNotice(NOTICE_ID, "새 제목", " "));

		// then
		assertThat(e.getField()).isEqualTo("body");
		assertThat(notice.getTitle()).isEqualTo("옛 제목");
	}

	// ---- deleteNotice ----

	@Test
	@DisplayName("지운 줄이 없으면 NOTICE_NOT_FOUND다")
	void 지운_줄이_없으면_NOTICE_NOT_FOUND() {
		// given
		given(notices.deleteNotice(NOTICE_ID)).willReturn(0);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.deleteNotice(NOTICE_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTICE_NOT_FOUND);
	}

	@Test
	@DisplayName("지운 줄이 있으면 오류 없이 끝난다")
	void 지운다() {
		// given
		given(notices.deleteNotice(NOTICE_ID)).willReturn(1);

		// when
		service.deleteNotice(NOTICE_ID);

		// then
		verify(notices).deleteNotice(NOTICE_ID);
	}

	// ---- user ----

	@Test
	@DisplayName("프로필이 없거나 닉네임이 없으면 둘 다 null이다")
	void 닉네임이_없으면_null() {
		// given
		UserProfile noNickname = new UserProfile();
		ReflectionTestUtils.setField(noNickname, "friendCode", "ABCD1234");
		given(profiles.findById(1L)).willReturn(Optional.empty());
		given(profiles.findById(2L)).willReturn(Optional.of(noNickname));

		// when & then
		assertThat(service.user(1L)).isEqualTo(new AdminUser(null, null));
		assertThat(service.user(2L)).isEqualTo(new AdminUser(null, null));
	}

	@Test
	@DisplayName("닉네임과 친구 코드를 준다")
	void 닉네임과_친구_코드() {
		// given
		UserProfile profile = new UserProfile();
		ReflectionTestUtils.setField(profile, "nickname", "웃음");
		ReflectionTestUtils.setField(profile, "friendCode", "ABCD1234");
		given(profiles.findById(ME)).willReturn(Optional.of(profile));

		// when
		AdminUser user = service.user(ME);

		// then
		assertThat(user).isEqualTo(new AdminUser("웃음", "ABCD1234"));
	}

	// ---- suspend ----

	@Test
	@DisplayName("정지는 기한(영구면 null)을 그대로 넘기고 풀기는 줄을 지운다")
	void 정지와_풀기() {
		// given
		OffsetDateTime until = OffsetDateTime.now().plusDays(3);

		// when
		service.suspend(ME, until);
		service.suspend(ME + 1, null);
		service.unsuspend(ME);

		// then
		verify(suspensions).upsert(ME, until);
		verify(suspensions).upsert(ME + 1, null);
		verify(suspensions).deleteAllByUserId(ME);
	}

	// ---- send ----

	@Test
	@DisplayName("분류가 BUG·OPINION이 아니면 category 칸 INVALID_REQUEST다")
	void 분류가_틀리면_INVALID_REQUEST() {
		// when
		ApiException wrong = catchThrowableOfType(ApiException.class,
				() -> service.send(ME, new FeedbackRequest("PRAISE", "내용", "1.0", "Pixel")));
		ApiException none = catchThrowableOfType(ApiException.class,
				() -> service.send(ME, new FeedbackRequest(null, "내용", "1.0", "Pixel")));

		// then
		assertThat(wrong.getField()).isEqualTo("category");
		assertThat(none.getField()).isEqualTo("category");
		verifyNoInteractions(feedback);
	}

	@Test
	@DisplayName("내용이 1001자면 text 칸 INVALID_REQUEST다")
	void 내용이_길면_INVALID_REQUEST() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.send(ME,
				new FeedbackRequest("BUG", "가".repeat(AdminService.FEEDBACK_MAX + 1), "1.0", "Pixel")));

		// then
		assertThat(e.getField()).isEqualTo("text");
		verifyNoInteractions(feedback);
	}

	@Test
	@DisplayName("앱 버전이 없으면 appVersion, 기기 모델이 101자면 deviceModel 칸 INVALID_REQUEST다")
	void 앱_버전과_기기_모델을_본다() {
		// when
		ApiException version = catchThrowableOfType(ApiException.class,
				() -> service.send(ME, new FeedbackRequest("BUG", "내용", null, "Pixel")));
		ApiException device = catchThrowableOfType(ApiException.class,
				() -> service.send(ME, new FeedbackRequest("BUG", "내용", "1.0", "a".repeat(101))));

		// then
		assertThat(version.getField()).isEqualTo("appVersion");
		assertThat(device.getField()).isEqualTo("deviceModel");
		verifyNoInteractions(feedback);
	}

	@Test
	@DisplayName("24시간 안에 10번 보냈으면 TOO_MANY_REQUESTS이고 저장하지 않는다")
	void 열_번이면_TOO_MANY_REQUESTS() {
		// given
		given(feedback.countSentSince(eq(ME), any())).willReturn((long) AdminService.FEEDBACK_PER_DAY);

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.send(ME, new FeedbackRequest("BUG", "내용", "1.0", "Pixel")));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
		verify(feedback, never()).save(any());
	}

	@Test
	@DisplayName("9번이면 회원마다 잠근 뒤 24시간 안의 수를 세고 다듬은 값으로 저장한다")
	void 아홉_번이면_저장한다() {
		// given
		given(feedback.countSentSince(eq(ME), any())).willReturn(9L);
		given(feedback.save(any(Feedback.class))).willAnswer(inv -> {
			Feedback f = inv.getArgument(0);
			ReflectionTestUtils.setField(f, "id", 77L);
			return f;
		});

		// when
		FeedbackItem item = service.send(ME, new FeedbackRequest("OPINION", "  좋아요  ", " 1.2.0 ", " Pixel 8 "));

		// then
		InOrder order = inOrder(feedback);
		order.verify(feedback).lockSender(7002, (int) ME);
		ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
		order.verify(feedback).countSentSince(eq(ME), since.capture());
		order.verify(feedback).save(any(Feedback.class));
		assertThat(since.getValue()).isCloseTo(OffsetDateTime.now().minusDays(1), within(5, ChronoUnit.SECONDS));
		assertThat(item.id()).isEqualTo(77L);
		assertThat(item.userId()).isEqualTo(ME);
		assertThat(item.category()).isEqualTo("OPINION");
		assertThat(item.text()).isEqualTo("좋아요");
		assertThat(item.appVersion()).isEqualTo("1.2.0");
		assertThat(item.deviceModel()).isEqualTo("Pixel 8");
		assertThat(item.status()).isEqualTo("OPEN");
	}

	@Test
	@DisplayName("잠금 키는 회원 ID의 위아래 32비트를 섞은 값이다")
	void 잠금_키() {
		// given
		long big = (1L << 32) | 3L;
		given(feedback.save(any(Feedback.class))).willAnswer(inv -> inv.getArgument(0));

		// when
		service.send(big, new FeedbackRequest("BUG", "내용", "1.0", "Pixel"));

		// then
		verify(feedback).lockSender(7002, 2);
	}

	// ---- feedbackList ----

	@Test
	@DisplayName("모르는 상태 필터는 status 칸 INVALID_REQUEST다")
	void 모르는_상태_필터는_INVALID_REQUEST() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.feedbackList("CLOSED", null));

		// then
		assertThat(e.getField()).isEqualTo("status");
		verifyNoInteractions(feedback);
	}

	@Test
	@DisplayName("숫자가 아닌 cursor는 cursor 칸 INVALID_REQUEST다")
	void 틀린_cursor는_INVALID_REQUEST() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.feedbackList(null, "abc"));

		// then
		assertThat(e.getField()).isEqualTo("cursor");
		verifyNoInteractions(feedback);
	}

	@Test
	@DisplayName("필터가 없으면 전부에서 21개를 읽고 20개를 넘으면 20번째 id를 다음 cursor로 준다")
	void 넘치면_다음_cursor를_준다() {
		// given
		List<Feedback> rows = LongStream.rangeClosed(1, 21).map(i -> 100 - i).mapToObj(AdminServiceTest::feedbackRow).toList();
		given(feedback.findPage(null, Limit.of(AdminService.PAGE_SIZE + 1))).willReturn(rows);

		// when
		FeedbackPage page = service.feedbackList(null, null);

		// then
		assertThat(page.items()).hasSize(AdminService.PAGE_SIZE);
		assertThat(page.nextCursor()).isEqualTo("80");
		verify(feedback, never()).findPageByStatus(any(), any(), any());
	}

	@Test
	@DisplayName("상태 필터와 cursor를 넘기고 20개 이하면 다음 cursor가 null이다")
	void 마지막_페이지면_cursor가_null() {
		// given
		given(feedback.findPageByStatus("DONE", 50L, Limit.of(AdminService.PAGE_SIZE + 1)))
			.willReturn(List.of(feedbackRow(49L), feedbackRow(48L)));

		// when
		FeedbackPage page = service.feedbackList("DONE", "50");

		// then
		assertThat(page.items()).extracting(FeedbackItem::id).containsExactly(49L, 48L);
		assertThat(page.nextCursor()).isNull();
		verify(feedback, never()).findPage(any(), any());
	}

	// ---- markFeedback ----

	@Test
	@DisplayName("상태가 OPEN·DONE이 아니면 status 칸 INVALID_REQUEST다")
	void 바꿀_상태가_틀리면_INVALID_REQUEST() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.markFeedback(1L, null));

		// then
		assertThat(e.getField()).isEqualTo("status");
		verifyNoInteractions(feedback);
	}

	@Test
	@DisplayName("없는 의견은 FEEDBACK_NOT_FOUND다")
	void 없는_의견은_FEEDBACK_NOT_FOUND() {
		// given
		given(feedback.findById(1L)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.markFeedback(1L, "DONE"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FEEDBACK_NOT_FOUND);
	}

	@Test
	@DisplayName("처리 상태를 바꾸고 바뀐 값을 돌려준다")
	void 처리_상태를_바꾼다() {
		// given
		Feedback row = feedbackRow(1L);
		given(feedback.findById(1L)).willReturn(Optional.of(row));

		// when
		FeedbackItem item = service.markFeedback(1L, "DONE");

		// then
		assertThat(row.getStatus()).isEqualTo("DONE");
		assertThat(item.status()).isEqualTo("DONE");
	}

	// ---- 도움 ----

	// id를 붙인 공지사항
	private static Notice notice(String title, String body) {
		Notice n = new Notice(title, body);
		ReflectionTestUtils.setField(n, "id", NOTICE_ID);
		return n;
	}

	// id를 붙인 오류 신고·의견
	private static Feedback feedbackRow(long id) {
		Feedback f = new Feedback(ME, "BUG", "내용", "1.0", "Pixel");
		ReflectionTestUtils.setField(f, "id", id);
		return f;
	}

}
