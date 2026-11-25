package com.lolog.api.internal.job;

import com.lolog.api.domain.character.service.ProfileCharacterService;
import com.lolog.api.domain.character.service.VoiceCloneService;
import com.lolog.api.domain.comic.service.ComicService;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.text.service.TextService;
import com.lolog.api.domain.video.service.VideoService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.internal.recording.DetectResultService;
import org.springframework.stereotype.Service;

// POST /internal/jobs/{id}/fail을 작업 종류에 따라 감지(#3), 만화(#5), 프로필 캐릭터, 내 목소리, 영상(video.md), 글(texts.md)로 나눈다.
@Service
public class JobFailService {

	private final JobRepository jobs;
	private final DetectResultService detectResults;
	private final ComicService comics;
	private final ProfileCharacterService profileCharacters;
	private final VideoService videos;
	private final TextService texts;
	private final VoiceCloneService voiceClones;

	/** 쓰는 저장소와 도구를 받는다. */
	public JobFailService(JobRepository jobs, DetectResultService detectResults, ComicService comics,
			ProfileCharacterService profileCharacters, VideoService videos, TextService texts,
			VoiceCloneService voiceClones) {
		this.voiceClones = voiceClones;
		this.texts = texts;
		this.videos = videos;
		this.jobs = jobs;
		this.detectResults = detectResults;
		this.comics = comics;
		this.profileCharacters = profileCharacters;
	}

	/**
	 * 작업 종류를 보고 맞는 서비스에 실패를 넘긴다.
	 *
	 * @param jobId 실패한 작업 ID
	 * @param reason 워커가 보낸 실패 이유
	 */
	public void fail(long jobId, String reason) {
		String type = jobs.findTypeById(jobId).orElseThrow(() -> ApiException.invalid(null));
		if ("COMIC".equals(type)) {
			comics.fail(jobId, reason);
		}
		else if ("AVATAR".equals(type)) {
			profileCharacters.fail(jobId, reason);
		}
		else if ("VOICE_CLONE".equals(type)) {
			voiceClones.fail(jobId, reason);
		}
		else if ("TEXT".equals(type)) {
			texts.fail(jobId, reason);
		}
		else if ("VIDEO_VOICE".equals(type) || "VIDEO_DRAW".equals(type)) {
			videos.fail(jobId, reason);
		}
		else {
			detectResults.fail(jobId, reason);
		}
	}

}
