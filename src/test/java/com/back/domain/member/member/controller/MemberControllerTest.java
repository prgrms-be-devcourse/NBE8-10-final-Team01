package com.back.domain.member.member.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    @DisplayName("정상적인 회원가입 요청 시 200 응답을 반환한다")
    void join_success() throws Exception {
        // given
        String requestBody = """
                {
                    "name": "테스터",
                    "email": "test@example.com",
                    "password": "Test1234!",
                    "passwordConfirm": "Test1234!"
                }
                """;

        // when & then
        mockMvc.perform(post("/api/v1/members/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200"))
                .andExpect(jsonPath("$.msg").value("회원가입성공"));
    }

    @Test
    @DisplayName("내정보 조회 요청 시 회원 정보를 반환한다")
    void getMyInfo_success() throws Exception {
        // given
        Member member = memberRepository.save(Member.createUser("내정보유저", "me-test@example.com", "encoded-password"));

        // when & then
        mockMvc.perform(get("/api/v1/members/me").header("X-Member-Id", member.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200"))
                .andExpect(jsonPath("$.msg").value("내 정보 조회 성공"))
                .andExpect(jsonPath("$.data.memberId").value(member.getId()))
                .andExpect(jsonPath("$.data.nickname").value("내정보유저"))
                .andExpect(jsonPath("$.data.email").value("me-test@example.com"))
                .andExpect(jsonPath("$.data.score").value(0))
                .andExpect(jsonPath("$.data.tier").isEmpty())
                .andExpect(jsonPath("$.data.role").value("USER"));
    }
}
