package com.lolog.api.domain.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lolog.api.domain.admin.repository.FeedbackRepository;
import com.lolog.api.domain.blog.repository.BlogPostRepository;
import com.lolog.api.domain.blog.repository.BlogSourceRepository;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.comic.repository.ComicRepository;
import com.lolog.api.domain.friend.repository.FriendshipRepository;
import com.lolog.api.domain.friend.repository.UserBlockRepository;
import com.lolog.api.domain.inbox.repository.BadgeReadRepository;
import com.lolog.api.domain.inbox.repository.NoticeReadRepository;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.notification.repository.NotificationRepository;
import com.lolog.api.domain.notification.service.DeviceTokenService;
import com.lolog.api.domain.recording.repository.RecordingRepository;
import com.lolog.api.domain.room.repository.RoomInviteRepository;
import com.lolog.api.domain.room.repository.RoomMemberRepository;
import com.lolog.api.domain.room.repository.RoomMessageRepository;
import com.lolog.api.domain.room.repository.RoomPostLikeRepository;
import com.lolog.api.domain.room.repository.RoomRepository;
import com.lolog.api.domain.room.service.RoomService;
import com.lolog.api.domain.text.repository.TextRepository;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import com.lolog.api.domain.user.repository.UserSuspensionRepository;
import com.lolog.api.domain.video.repository.VideoRepository;
import com.lolog.api.global.storage.ImageStorage;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("UserDataService")
@ExtendWith(MockitoExtension.class)
class UserDataServiceTest {

	private static final long USER = 7L;

	@Mock
	ImageStorage storage;
	@Mock
	TransactionTemplate tx;
	@Mock
	DeviceTokenService deviceTokens;
	@Mock
	RoomService rooms;
	@Mock
	JobRepository jobs;
	@Mock
	ComicRepository comics;
	@Mock
	VideoRepository videos;
	@Mock
	CardRepository cards;
	@Mock
	TextRepository texts;
	@Mock
	BlogPostRepository blogPosts;
	@Mock
	BlogSourceRepository blogSources;
	@Mock
	RecordingRepository recordings;
	@Mock
	NotificationRepository notifications;
	@Mock
	NoticeReadRepository noticeReads;
	@Mock
	BadgeReadRepository badgeReads;
	@Mock
	FeedbackRepository feedback;
	@Mock
	UserSuspensionRepository suspensions;
	@Mock
	RoomPostLikeRepository postLikes;
	@Mock
	RoomMessageRepository messages;
	@Mock
	RoomInviteRepository invites;
	@Mock
	RoomRepository roomRepository;
	@Mock
	RoomMemberRepository members;
	@Mock
	FriendshipRepository friendships;
	@Mock
	UserBlockRepository blocks;
	@Mock
	UserProfileRepository profiles;

	@InjectMocks
	UserDataService service;

	@Test
	@DisplayName("카드를 지운 뒤 글을 지우고, 방장 방은 멤버 줄과 빈 방을 지운 뒤 넘기며, DB를 다 지운 뒤 이미지를 지운다")
	void 정한_순서대로_지운다() {
		// given
		runTransaction();
		when(roomRepository.findIdsByOwnerId(USER)).thenReturn(List.of(31L, 32L));

		// when
		service.delete(USER);

		// then
		InOrder order = inOrder(jobs, comics, videos, cards, texts, blogPosts, blogSources, recordings, deviceTokens,
				notifications, noticeReads, badgeReads, feedback, suspensions, postLikes, messages, invites,
				roomRepository, members, rooms, friendships, blocks, profiles, storage);
		order.verify(jobs).deleteAllByUserId(USER);
		order.verify(comics).deleteAllByUserId(USER);
		order.verify(videos).deleteAllByUserId(USER);
		order.verify(cards).deleteAllByUserId(USER);
		order.verify(texts).deleteAllByUserId(USER);
		order.verify(blogPosts).deleteAllByUserId(USER);
		order.verify(blogSources).deleteAllByUserId(USER);
		order.verify(recordings).deleteAllByUserId(USER);
		order.verify(deviceTokens).removeAll(USER);
		order.verify(notifications).deleteAllByUserId(USER);
		order.verify(noticeReads).deleteAllByUserId(USER);
		order.verify(badgeReads).deleteAllByUserId(USER);
		order.verify(feedback).clearUser(USER);
		order.verify(suspensions).deleteAllByUserId(USER);
		order.verify(cards).detachProfileCharacter("7");
		order.verify(postLikes).deleteAllByUserId(USER);
		order.verify(messages).deleteAllByUserId(USER);
		order.verify(invites).deleteAllByUserId(USER);
		order.verify(roomRepository).findIdsByOwnerId(USER);
		order.verify(members).deleteAllByUserId(USER);
		order.verify(roomRepository).deleteAllEmpty();
		order.verify(rooms).removeEmptyOrHandOver(31L);
		order.verify(rooms).removeEmptyOrHandOver(32L);
		order.verify(friendships).deleteAllOf(USER);
		order.verify(blocks).deleteAllOf(USER);
		order.verify(profiles).deleteAllByUserId(USER);
		order.verify(storage).deleteUser(USER);
	}

	@Test
	@DisplayName("DB 삭제가 실패하면 이미지를 지우지 않는다")
	void DB_삭제가_실패하면_이미지를_지우지_않는다() {
		// given
		runTransaction();
		doThrow(new IllegalStateException("DB")).when(cards).deleteAllByUserId(USER);

		// when & then
		assertThatThrownBy(() -> service.delete(USER)).isInstanceOf(IllegalStateException.class);
		verify(storage, never()).deleteUser(anyLong());
	}

	@Test
	@DisplayName("이미지 삭제가 실패하면 예외를 그대로 던진다")
	void 이미지_삭제_실패는_그대로_던진다() {
		// given
		runTransaction();
		doThrow(new IllegalStateException("저장소")).when(storage).deleteUser(USER);

		// when & then
		assertThatThrownBy(() -> service.delete(USER)).isInstanceOf(IllegalStateException.class)
			.hasMessage("저장소");
	}

	// 트랜잭션 콜백을 바로 실행한다.
	private void runTransaction() {
		doAnswer(inv -> {
			inv.<Consumer<TransactionStatus>>getArgument(0).accept(null);
			return null;
		}).when(tx).executeWithoutResult(any());
	}

}
