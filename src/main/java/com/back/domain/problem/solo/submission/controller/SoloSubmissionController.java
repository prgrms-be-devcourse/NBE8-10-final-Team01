package com.back.domain.problem.solo.submission.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.problem.solo.submission.dto.SoloSubmitRequest;
import com.back.domain.problem.solo.submission.service.SoloSubmissionService;
import com.back.domain.problem.submission.dto.SubmissionResponse;
import com.back.global.rq.Rq;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/solo/submissions")
@RequiredArgsConstructor
public class SoloSubmissionController {

    private final SoloSubmissionService soloSubmissionService;
    private final Rq rq;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubmissionResponse submit(@RequestBody SoloSubmitRequest request) {
        return soloSubmissionService.submit(request, rq.getActor().getId());
    }
}
