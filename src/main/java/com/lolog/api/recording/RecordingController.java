package com.lolog.api.recording;

import com.lolog.api.auth.CurrentUserProvider;
import com.lolog.api.recording.RecordingDtos.StatusResponse;
import com.lolog.api.recording.RecordingDtos.UploadRequest;
import com.lolog.api.recording.RecordingDtos.UploadResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recordings")
public class RecordingController {

	private final RecordingService recordingService;
	private final CurrentUserProvider currentUser;

	public RecordingController(RecordingService recordingService, CurrentUserProvider currentUser) {
		this.recordingService = recordingService;
		this.currentUser = currentUser;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public UploadResponse upload(@Valid @RequestBody UploadRequest request) {
		return recordingService.upload(currentUser.currentUserId(), request);
	}

	@GetMapping("/{id}")
	public StatusResponse status(@PathVariable Long id) {
		return recordingService.status(currentUser.currentUserId(), id);
	}

}
