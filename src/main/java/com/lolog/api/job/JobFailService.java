package com.lolog.api.job;

import com.lolog.api.character.ProfileCharacterService;
import com.lolog.api.comic.ComicService;
import com.lolog.api.common.ApiException;
import com.lolog.api.recording.DetectResultService;
import org.springframework.stereotype.Service;

// POST /internal/jobs/{id}/fail을 작업 종류에 따라 감지(#3)나 만화(#5)로 나눈다.
@Service
public class JobFailService {

	private final JobRepository jobs;
	private final DetectResultService detectResults;
	private final ComicService comics;
	private final ProfileCharacterService profileCharacters;

	public JobFailService(JobRepository jobs, DetectResultService detectResults, ComicService comics,
			ProfileCharacterService profileCharacters) {
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
		else {
			detectResults.fail(jobId, reason);
		}
	}

}
