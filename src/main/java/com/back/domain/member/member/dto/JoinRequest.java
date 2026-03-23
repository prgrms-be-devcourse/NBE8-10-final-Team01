package com.back.domain.member.member.dto;

// 회원가입 요청 바디(JSON) 구조
public record JoinRequest(String name, String email, String password, String passwordConfirm) {}
