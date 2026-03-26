package com.back.domain.problem.submission.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.problem.submission.dto.SubmissionResponse;
import com.back.domain.problem.submission.dto.SubmitRequest;
import com.back.domain.problem.submission.service.SubmissionService;
import com.back.global.rq.Rq;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/submissions")
@RequiredArgsConstructor
public class SubmissionController {

    private final SubmissionService submissionService;
    private final Rq rq;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubmissionResponse submit(@RequestBody SubmitRequest request) {
        return submissionService.submit(request, rq.getActor().getId());
    }
}
