package com.lolog.api.job;

import com.lolog.api.character.ProfileCharacterService;
import com.lolog.api.character.VoiceCloneService;
import com.lolog.api.comic.ComicService;
import com.lolog.api.common.ApiException;
import com.lolog.api.recording.DetectResultService;
import com.lolog.api.text.TextService;
import com.lolog.api.video.VideoService;
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

	public void fail(long jobId, String reason) {
		String type = jobs.findTypeById(jobId).orElseThrow(() -> ApiException.invalid(null, "작업을 찾을 수 없습니다."));
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
