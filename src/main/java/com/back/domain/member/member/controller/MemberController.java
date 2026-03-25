package com.back.domain.member.member.controller;

import org.springframework.web.bind.annotation.*;

import com.back.domain.battle.result.dto.MyBattleResultsResponse;
import com.back.domain.battle.result.service.BattleResultService;
import com.back.domain.member.member.dto.JoinRequest;
import com.back.domain.member.member.dto.LoginRequest;
import com.back.domain.member.member.dto.MyInfoResponse;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.service.MemberService;
import com.back.global.exception.ServiceException;
import com.back.global.rq.Rq;
import com.back.global.rsData.RsData;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/members")
public class MemberController {
    private final MemberService memberService;
    private final BattleResultService battleResultService;
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

    /**
     * 내 전적 조회 API
     *
     * /api/v1/members/me/battle-results?page=0&size=20
     *
     * - memberId를 직접 받지 않고
     * - rq.getActor() 로 현재 로그인 사용자를 가져온다.
     * - 응답은 MemberController 스타일에 맞게 RsData 로 감싼다.
     */
    @GetMapping("/me/battle-results")
    public RsData<MyBattleResultsResponse> getMyBattleResults(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {

        // 현재 로그인 사용자 조회
        Member actor = rq.getActor();

        // 인증 정보가 없으면 로그인 필요 예외
        if (actor == null) {
            throw new ServiceException("MEMBER_401", "로그인이 필요합니다.");
        }

        // 배틀 도메인 서비스에서 실제 전적 조회 수행
        MyBattleResultsResponse response = battleResultService.getMyBattleResults(actor.getId(), page, size);

        // MemberController 쪽 응답 규약에 맞춰 RsData 로 감싸서 반환
        return RsData.of("200", "내 전적 조회 성공", response);
    }
  
    // 내정보 조회
    @GetMapping("/me")
    public RsData<MyInfoResponse> getMyInfo() {
        Member actor = rq.getActor();
        if (actor == null || actor.getId() == null) {
            throw new ServiceException("MEMBER_401", "로그인이 필요합니다");
        }

        return memberService.getMyInfo(actor.getId());
    }
}
