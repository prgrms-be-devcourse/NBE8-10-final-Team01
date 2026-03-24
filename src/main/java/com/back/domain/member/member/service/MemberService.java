package com.back.domain.member.member.service;

import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import com.back.domain.member.member.dto.JoinRequest;
import com.back.domain.member.member.dto.LoginRequest;
import com.back.domain.member.member.dto.MyInfoResponse;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.global.exception.ServiceException;
import com.back.global.jwt.JwtProvider;
import com.back.global.rsData.RsData;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Service
@Validated
@RequiredArgsConstructor
public class MemberService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    public RsData<Void> join(@Valid JoinRequest req) {

        // req null 방어
        if (req == null) {
            throw new ServiceException("MEMBER_400", "요청 바디가 비어 있습니다");
        }

        // 비밀번호 확인 일치 검증
        if (!req.password().equals(req.passwordConfirm())) {
            throw new ServiceException("MEMBER_400-1", "비밀번호와 비밀번호 확인이 일치하지 않습니다");
        }

        // 이메일 정규화 (입력값 보호)
        String normalizedEmail = req.email().trim().toLowerCase(Locale.ROOT);

        // 이메일 중복 체크
        if (memberRepository.existsByEmail(normalizedEmail)) {
            throw new ServiceException("MEMBER_409", "이미 존재하는 이메일입니다");
        }

        // 비밀번호 암호화
        String encodedPassword = passwordEncoder.encode(req.password());

        // 회원 생성 (엔티티 팩토리 메서드 사용)
        Member member = Member.createUser(req.name(), normalizedEmail, encodedPassword);

        memberRepository.save(member);

        return RsData.of("200", "회원가입성공");
    }

    public RsData<MyInfoResponse> getMyInfo(Long memberId) {
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("MEMBER_400", "유효한 회원 ID가 필요합니다");
        }

        Member member = memberRepository
                .findById(memberId)
                .orElseThrow(() -> new ServiceException("MEMBER_404", "존재하지 않는 회원입니다"));

        return RsData.of("200", "내 정보 조회 성공", MyInfoResponse.from(member));
    }

    // 로그인 — 인증 성공 시 accessToken 문자열 반환 (쿠키 설정은 컨트롤러가 담당)
    public String login(@Valid LoginRequest req) {

        // req null 방어
        if (req == null) {
            throw new ServiceException("MEMBER_400", "요청 바디가 비어 있습니다");
        }

        // 이메일 정규화
        String normalizedEmail = req.email().trim().toLowerCase(Locale.ROOT);

        // 이메일로 회원 조회 (없으면 보안상 동일 메시지)
        Member member = memberRepository
                .findByEmail(normalizedEmail)
                .orElseThrow(() -> new ServiceException("MEMBER_401", "이메일 또는 비밀번호가 올바르지 않습니다"));

        // 비밀번호 검증
        if (!passwordEncoder.matches(req.password(), member.getPassword())) {
            throw new ServiceException("MEMBER_401", "이메일 또는 비밀번호가 올바르지 않습니다");
        }

        // JWT 토큰 생성 후 반환
        return jwtProvider.createToken(
                member.getId(),
                member.getEmail(),
                member.getNickname(),
                member.getRole().getKey());
    }
}
