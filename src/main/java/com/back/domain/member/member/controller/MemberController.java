package com.back.domain.member.member.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.back.domain.member.member.dto.JoinRequest;
import com.back.domain.member.member.service.MemberService;
import com.back.global.response.ApiResponse;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/members")
public class MemberController {
    private final MemberService memberService;

    // 회원가입
    @PostMapping("/join")
    public ResponseEntity<ApiResponse> join(@RequestBody JoinRequest req) {
        ApiResponse res = memberService.join(req);
        return ResponseEntity.ok(res);
    }
}
