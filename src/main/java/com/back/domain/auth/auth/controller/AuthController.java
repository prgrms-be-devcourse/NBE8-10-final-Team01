package com.back.domain.auth.auth.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.global.exception.ServiceException;
import com.back.global.jwt.JwtProvider;
import com.back.global.jwt.RefreshTokenService;
import com.back.global.rq.Rq;
import com.back.global.rsData.RsData;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/auth")
public class AuthController {

    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;
    private final MemberRepository memberRepository;
    private final Rq rq;

    // accessToken 재발급 — refreshToken 쿠키로 검증
    @PostMapping("/reissue")
    public RsData<Void> reissue() {
        String refreshToken = rq.getCookieValue("refreshToken", null);

        if (refreshToken == null || !jwtProvider.validateToken(refreshToken)) {
            throw new ServiceException("AUTH_401", "유효하지 않은 refreshToken입니다");
        }

        Claims claims = jwtProvider.parseToken(refreshToken);
        Long memberId = Long.parseLong(claims.getSubject());

        // Redis에 저장된 토큰과 비교
        String stored = refreshTokenService.get(memberId);
        if (!refreshToken.equals(stored)) {
            throw new ServiceException("AUTH_401", "만료되었거나 이미 사용된 refreshToken입니다");
        }

        // 회원 정보 조회 후 새 accessToken 발급
        Member member = memberRepository
                .findById(memberId)
                .orElseThrow(() -> new ServiceException("AUTH_404", "존재하지 않는 회원입니다"));

        String newAccessToken = jwtProvider.createToken(
                member.getId(), member.getEmail(), member.getNickname(), member.getRole().getKey());

        rq.setCookie("accessToken", newAccessToken);
        return RsData.of("200", "accessToken이 재발급되었습니다");
    }
}
