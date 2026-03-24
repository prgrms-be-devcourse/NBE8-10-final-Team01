package com.back.global.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

// Spring Security가 인증 시 유저 정보를 로드하는 서비스
// 현재는 JWT 필터가 직접 인증을 처리하므로 실제로는 OAuth2 도입 시 자동 호출됨
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;

    // 이메일로 회원을 조회해 SecurityUser로 변환 후 반환
    // OAuth2 / 폼 로그인 도입 시 Spring Security가 이 메서드를 자동 호출
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // 이메일로 회원 조회 — 없으면 Spring Security 표준 예외 발생
        Member member = memberRepository
                .findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("존재하지 않는 회원입니다: " + email));

        // 조회한 Member 데이터를 SecurityUser로 변환해 반환
        return new SecurityUser(
                member.getId(),
                member.getEmail(),
                member.getNickname(),
                member.getRole().getKey());
    }
}
