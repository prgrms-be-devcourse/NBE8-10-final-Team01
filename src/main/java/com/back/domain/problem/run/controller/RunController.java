package com.back.domain.problem.run.controller;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.problem.run.dto.RunRequest;
import com.back.domain.problem.run.service.RunService;
import com.back.global.security.SecurityUser;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/run")
@RequiredArgsConstructor
public class RunController {

    private final RunService runService;

    @PostMapping
    public Map<String, String> run(
            @RequestBody RunRequest request, @AuthenticationPrincipal SecurityUser securityUser) {
        return runService.run(request, securityUser.getId());
    }
}
