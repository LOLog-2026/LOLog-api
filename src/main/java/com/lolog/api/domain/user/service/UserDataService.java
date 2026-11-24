package com.lolog.api.domain.user.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 탈퇴한 사용자의 데이터를 모두 지운다(FR-AUTH-05). lolog-auth가 DELETE /internal/users/{id}로 부른다.
// 같은 요청을 여러 번 불러도 된다. 이미지를 지우지 못하면 예외(500)로 답하고, lolog-auth가 204가 올 때까지 다시 부른다(LOLog-docs PR #28).
@Service
public class UserDataService {

	private final ImageStorage storage;
	private final TransactionTemplate tx;
	private final DeviceTokenService deviceTokens;
	private final RoomService rooms;
	private final JobRepository jobs;
	private final ComicRepository comics;
	private final VideoRepository videos;
	private final CardRepository cards;
	private final TextRepository texts;
	private final BlogPostRepository blogPosts;
	private final BlogSourceRepository blogSources;
	private final RecordingRepository recordings;
	private final NotificationRepository notifications;
	private final NoticeReadRepository noticeReads;
	private final BadgeReadRepository badgeReads;
	private final FeedbackRepository feedback;
	private final UserSuspensionRepository suspensions;
	private final RoomPostLikeRepository postLikes;
	private final RoomMessageRepository messages;
	private final RoomInviteRepository invites;
	private final RoomRepository roomRepository;
	private final RoomMemberRepository members;
	private final FriendshipRepository friendships;
	private final UserBlockRepository blocks;
	private final UserProfileRepository profiles;

	public UserDataService(ImageStorage storage, TransactionTemplate tx, DeviceTokenService deviceTokens,
			RoomService rooms, JobRepository jobs, ComicRepository comics, VideoRepository videos, CardRepository cards,
			TextRepository texts, BlogPostRepository blogPosts, BlogSourceRepository blogSources,
			RecordingRepository recordings, NotificationRepository notifications, NoticeReadRepository noticeReads,
			BadgeReadRepository badgeReads, FeedbackRepository feedback, UserSuspensionRepository suspensions,
			RoomPostLikeRepository postLikes, RoomMessageRepository messages, RoomInviteRepository invites,
			RoomRepository roomRepository, RoomMemberRepository members, FriendshipRepository friendships,
			UserBlockRepository blocks, UserProfileRepository profiles) {
		this.storage = storage;
		this.tx = tx;
		this.deviceTokens = deviceTokens;
		this.rooms = rooms;
		this.jobs = jobs;
		this.comics = comics;
		this.videos = videos;
		this.cards = cards;
		this.texts = texts;
		this.blogPosts = blogPosts;
		this.blogSources = blogSources;
		this.recordings = recordings;
		this.notifications = notifications;
		this.noticeReads = noticeReads;
		this.badgeReads = badgeReads;
		this.feedback = feedback;
		this.suspensions = suspensions;
		this.postLikes = postLikes;
		this.messages = messages;
		this.invites = invites;
		this.roomRepository = roomRepository;
		this.members = members;
		this.friendships = friendships;
		this.blocks = blocks;
		this.profiles = profiles;
	}

	// 회원 한 명의 데이터를 지운다.
	public void delete(long userId) {
		// 한 트랜잭션에서 지운다. 화자·대사·묶음은 DB가 함께 지운다(ON DELETE CASCADE).
		// 진행 중인 감지·만화 작업도 지워지므로 워커의 다음 요청은 404·400이 된다(작업 취소).
		tx.executeWithoutResult(status -> {
			jobs.deleteAllByUserId(userId);
			comics.deleteAllByUserId(userId);
			// 카드 영상(video.md 7장). 파일은 아래 users/{userId}/ 아래와 함께 지워진다.
			videos.deleteAllByUserId(userId);
			cards.deleteAllByUserId(userId);
			// 글로 만든 카드의 글(texts.md 2장). 카드를 지운 뒤 지운다.
			texts.deleteAllByUserId(userId);
			// 블로그 글은 연결을 끊어도 남게 SET NULL이라 외래 키로 함께 지워지지 않아 따로 지운다(texts.md 7장).
			blogPosts.deleteAllByUserId(userId);
			blogSources.deleteAllByUserId(userId);
			recordings.deleteAllByUserId(userId);
			deviceTokens.removeAll(userId);
			// 알림 목록과 읽음·새 소식 기록(inbox.md 6장)
			notifications.deleteAllByUserId(userId);
			noticeReads.deleteAllByUserId(userId);
			badgeReads.deleteAllByUserId(userId);
			// 보낸 오류 신고·의견은 남기고 보낸 사람만 비운다(admin.md 5장, FR-AUTH-05의 예외). 정지 표시는 지운다.
			feedback.clearUser(userId);
			suspensions.deleteAllByUserId(userId);
			// 다른 사람 카드에 붙은 이 회원의 프로필 캐릭터를 뺀다(characters.md 3장). 이 회원의 카드는 위에서 지워졌다.
			cards.detachProfileCharacter(String.valueOf(userId));
			// 방(rooms.md): 이 회원이 올린 만화는 위에서 만화를 지울 때 외래 키로 함께 지워졌다(좋아요·대화 줄 포함).
			// 남긴 좋아요·댓글과 멤버 줄을 지우고, 멤버가 남지 않은 방을 지운다.
			postLikes.deleteAllByUserId(userId);
			messages.deleteAllByUserId(userId);
			// 받은 초대를 지우고, 방장이던 방은 가장 먼저 들어온 멤버에게 넘긴다(db.md V9 초안).
			invites.deleteAllByUserId(userId);
			List<Long> owned = roomRepository.findIdsByOwnerId(userId);
			members.deleteAllByUserId(userId);
			roomRepository.deleteAllEmpty();
			owned.forEach(rooms::removeEmptyOrHandOver);
			// 친구 관계·요청·차단은 이 회원이 어느 쪽이든 지운다. 친구들 목록에서는 이 회원이 빠지기만 한다(friends.md).
			friendships.deleteAllOf(userId);
			blocks.deleteAllOf(userId);
			profiles.deleteAllByUserId(userId);
		});
		// DB를 지운 뒤 이미지를 지운다. 실패하면 예외가 그대로 나가 500이 되고, 다시 부르면 남은 이미지만 지운다.
		storage.deleteUser(userId);
	}

}
