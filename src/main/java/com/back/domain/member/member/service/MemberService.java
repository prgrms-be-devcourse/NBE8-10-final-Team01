package com.back.domain.member.member.service;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import com.back.domain.member.member.dto.JoinRequest;
import com.back.global.response.ApiResponse;

@Service
@RequiredArgsConstructor
public class MemberService {
    public ApiResponse join(JoinRequest req) {
        return new ApiResponse();
    }
}
