package com.back.domain.problem.solo.run.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.problem.solo.run.dto.SoloRunRequest;
import com.back.domain.problem.solo.run.service.SoloRunService;
import com.back.global.rq.Rq;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/solo/run")
@RequiredArgsConstructor
public class SoloRunController {

    private final SoloRunService soloRunService;
    private final Rq rq;

    @PostMapping
    public Map<String, String> run(@RequestBody SoloRunRequest request) {
        return soloRunService.run(request, rq.getActor().getId());
    }
}
