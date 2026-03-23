package com.back.domain.member.member.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 회원가입 요청 바디(JSON) 구조
public record JoinRequest(
        @NotBlank(message = "이메일은 필수 입력값입니다")
        @Email(message = "올바른 이메일 형식이 아닙니다")
        String email,

        @NotBlank(message = "비밀번호는 필수 입력값입니다")
        @Pattern(
                regexp = "^(?=.*[a-zA-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]).{8,12}$",
                message = "비밀번호는 8~12자이며 영문, 숫자, 특수문자를 모두 포함해야 합니다"
        )
        String password,

        @NotBlank(message = "비밀번호 확인은 필수입니다")
        String passwordConfirm,

        @NotBlank(message = "닉네임은 필수 입력값입니다")
        @Size(min = 2, max = 20, message = "닉네임은 2~20자 사이여야 합니다")
        String name
) {
}