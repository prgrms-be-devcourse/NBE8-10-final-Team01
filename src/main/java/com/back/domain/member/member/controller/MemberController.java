package com.back.domain.member.member.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.member.member.dto.JoinRequest;
import com.back.domain.member.member.dto.MyInfoResponse;
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
        RsData<Void> res = memberService.join(req);
        return res;
    }

    // 내정보 조회
    @GetMapping("/me")
    public RsData<MyInfoResponse> getMyInfo(@RequestHeader("X-Member-Id") Long memberId) {
        // TODO: 인증 인프라 도입 후 X-Member-Id 헤더 제거하고 SecurityContext 기반으로 전환
        RsData<MyInfoResponse> res = memberService.getMyInfo(memberId);
        return res;
    }
}
