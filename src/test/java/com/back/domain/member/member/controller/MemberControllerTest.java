package com.back.domain.member.member.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String LOGIN_TEST_EMAIL = "logintest@example.com";
    private static final String LOGIN_TEST_PASSWORD = "Test1234!";

    @BeforeEach
    void setUp() {
        // 로그인 테스트용 회원 사전 등록
        memberRepository
                .findByEmail(LOGIN_TEST_EMAIL)
                .ifPresent(memberRepository::delete);
        Member member = Member.createUser("로그인테스터", LOGIN_TEST_EMAIL, passwordEncoder.encode(LOGIN_TEST_PASSWORD));
        memberRepository.save(member);
    }

    @AfterEach
    void tearDown() {
        memberRepository.findByEmail(LOGIN_TEST_EMAIL).ifPresent(memberRepository::delete);
    }

    // ============ 회원가입 ============

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

    // ============ 로그인 ============

    @Test
    @DisplayName("정상적인 로그인 요청 시 200 응답과 accessToken을 반환한다")
    void login_success() throws Exception {
        // given
        String requestBody = """
                {
                    "email": "%s",
                    "password": "%s"
                }
                """.formatted(LOGIN_TEST_EMAIL, LOGIN_TEST_PASSWORD);

        // when & then
        mockMvc.perform(post("/api/v1/members/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200"))
                .andExpect(jsonPath("$.msg").value("로그인 성공"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("존재하지 않는 이메일로 로그인 시 401 응답을 반환한다")
    void login_fail_email_not_found() throws Exception {
        // given
        String requestBody = """
                {
                    "email": "notexist@example.com",
                    "password": "Test1234!"
                }
                """;

        // when & then
        mockMvc.perform(post("/api/v1/members/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("MEMBER_401"))
                .andExpect(jsonPath("$.msg").value("이메일 또는 비밀번호가 올바르지 않습니다"));
    }

    @Test
    @DisplayName("잘못된 비밀번호로 로그인 시 401 응답을 반환한다")
    void login_fail_wrong_password() throws Exception {
        // given
        String requestBody = """
                {
                    "email": "%s",
                    "password": "Wrong1234!"
                }
                """.formatted(LOGIN_TEST_EMAIL);

        // when & then
        mockMvc.perform(post("/api/v1/members/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("MEMBER_401"))
                .andExpect(jsonPath("$.msg").value("이메일 또는 비밀번호가 올바르지 않습니다"));
    }

    @Test
    @DisplayName("이메일 없이 로그인 요청 시 400 응답을 반환한다")
    void login_fail_missing_email() throws Exception {
        // given
        String requestBody = """
                {
                    "password": "Test1234!"
                }
                """;

        // when & then
        mockMvc.perform(post("/api/v1/members/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("비밀번호 없이 로그인 요청 시 400 응답을 반환한다")
    void login_fail_missing_password() throws Exception {
        // given
        String requestBody = """
                {
                    "email": "%s"
                }
                """.formatted(LOGIN_TEST_EMAIL);

        // when & then
        mockMvc.perform(post("/api/v1/members/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }
}
