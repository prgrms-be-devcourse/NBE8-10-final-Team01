package com.back.domain.member.member.dto;

import com.back.domain.member.member.entity.Member;

public record MyInfoResponse(Long memberId, String nickname, String email, Long score, String tier, String role) {

    public static MyInfoResponse from(Member member) {
        return new MyInfoResponse(
                member.getId(),
                member.getNickname(),
                member.getEmail(),
                member.getScore(),
                member.getTier(),
                member.getRole().name());
    }
}
