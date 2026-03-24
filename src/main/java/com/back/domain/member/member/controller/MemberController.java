package com.back.domain.member.member.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.member.member.dto.JoinRequest;
import com.back.domain.member.member.dto.LoginRequest;
import com.back.domain.member.member.dto.LoginResponse;
import com.back.domain.member.member.service.MemberService;
import com.back.global.rsData.RsData;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/members")
public class MemberController {
    private final MemberService memberService;

    // 회원가입
    @PostMapping("/join")
    public RsData<Void> join(@Valid @RequestBody JoinRequest req) {
        return memberService.join(req);
    }

    // 로그인
    @PostMapping("/login")
    public RsData<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return memberService.login(req);
    }
}
