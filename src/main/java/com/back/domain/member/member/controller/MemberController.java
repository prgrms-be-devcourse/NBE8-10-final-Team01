package com.back.domain.member.member.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.member.member.dto.JoinRequest;
import com.back.domain.member.member.dto.LoginRequest;
import com.back.domain.member.member.service.MemberService;
import com.back.global.rq.Rq;
import com.back.global.rsData.RsData;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/members")
public class MemberController {
    private final MemberService memberService;
    private final Rq rq;

    // 회원가입
    @PostMapping("/join")
    public RsData<Void> join(@Valid @RequestBody JoinRequest req) {
        return memberService.join(req);
    }

    // 로그인 — 발급된 토큰을 HttpOnly 쿠키에 저장
    @PostMapping("/login")
    public RsData<Void> login(@Valid @RequestBody LoginRequest req) {
        String accessToken = memberService.login(req);
        rq.setCookie("accessToken", accessToken);
        return RsData.of("200", "로그인 성공");
    }

    // 로그아웃 — accessToken 쿠키 만료
    @PostMapping("/logout")
    public RsData<Void> logout() {
        rq.deleteCookie("accessToken");
        return RsData.of("200", "로그아웃 성공");
    }
}
