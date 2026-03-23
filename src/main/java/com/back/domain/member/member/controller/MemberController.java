package com.back.domain.member.member.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.member.member.dto.JoinRequest;
import com.back.domain.member.member.service.MemberService;
import com.back.global.rsData.RsData;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/members")
public class MemberController {
    private final MemberService memberService;

    // 회원가입
    @PostMapping("/join")
    public RsData<Void> join(@RequestBody JoinRequest req) {
        RsData<Void> res = memberService.join(req);
        return res;
    }
}
