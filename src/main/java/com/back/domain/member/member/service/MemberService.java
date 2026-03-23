package com.back.domain.member.member.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.back.domain.member.member.dto.JoinRequest;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.global.exception.ServiceException;
import com.back.global.rsData.RsData;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemberService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public RsData<Void> join(JoinRequest req) {

        // 요청값 검증
        if (req == null) {
            throw new ServiceException("MEMBER_400", "요청 바디가 비어 있습니다");
        }
        if (req.email() == null || req.email().isBlank()) {
            throw new ServiceException("MEMBER_400", "이메일은 필수 입력값입니다");
        }
        if (req.password() == null || req.password().isBlank()) {
            throw new ServiceException("MEMBER_400", "비밀번호는 필수 입력값입니다");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new ServiceException("MEMBER_400", "닉네임은 필수 입력값입니다");
        }

        // 비밀번호 확인 일치 검증
        if (!req.password().equals(req.passwordConfirm())) {
            throw new ServiceException("MEMBER_400", "비밀번호와 비밀번호 확인이 일치하지 않습니다");
        }

        // 비밀번호 정책 검증 (8~12자, 영문+숫자+특수문자 모두 포함)
        String passwordPattern = "^(?=.*[a-zA-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]).{8,12}$";
        if (!req.password().matches(passwordPattern)) {
            throw new ServiceException("MEMBER_400", "비밀번호는 8~12자이며 영문, 숫자, 특수문자를 모두 포함해야 합니다");
        }

        // 이메일 중복 체크
        if (memberRepository.existsByEmail(req.email())) {
            throw new ServiceException("MEMBER_409", "이미 존재하는 이메일입니다");
        }

        // 비밀번호 암호화
        String encodedPassword = passwordEncoder.encode(req.password());

        // 회원 생성 (엔티티 팩토리 메서드 사용)
        Member member = Member.createUser(req.name(), req.email(), encodedPassword);

        memberRepository.save(member);

        return RsData.of("200", "회원가입성공");
    }
}
