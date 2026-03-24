package com.back.domain.member.member.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.mock.web.MockCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;

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
        memberRepository.findByEmail(LOGIN_TEST_EMAIL).ifPresent(memberRepository::delete);
        Member member = Member.createUser("로그인테스터", LOGIN_TEST_EMAIL, passwordEncoder.encode(LOGIN_TEST_PASSWORD));
        memberRepository.save(member);
    }

    @AfterEach
    void tearDown() {
        memberRepository.findByEmail(LOGIN_TEST_EMAIL).ifPresent(memberRepository::delete);
        // join_success 테스트가 생성한 회원 정리 — 미삭제 시 재실행 때 중복 이메일로 실패
        memberRepository.findByEmail("test@example.com").ifPresent(memberRepository::delete);
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
    // ============ 로그인 ============
    @Test
    @DisplayName("정상적인 로그인 요청 시 200 응답과 accessToken 쿠키를 반환한다")
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
                // 응답 바디에 토큰이 없어야 함
                .andExpect(jsonPath("$.data").doesNotExist())
                // Set-Cookie 헤더에 accessToken 쿠키가 존재해야 함
                .andExpect(header().string("Set-Cookie", containsString("accessToken=")));
    }

    @Test
    @DisplayName("로그아웃 요청 시 200 응답과 accessToken 쿠키가 만료된다")
    void logout_success() throws Exception {
        // given — 먼저 로그인해서 쿠키 획득
        String loginBody = """
                {
                    "email": "%s",
                    "password": "%s"
                }
                """.formatted(LOGIN_TEST_EMAIL, LOGIN_TEST_PASSWORD);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/members/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andReturn();

        // 로그인 응답에서 accessToken 쿠키 추출
        String cookieHeader = loginResult.getResponse().getHeader("Set-Cookie");
        String token = cookieHeader.split("accessToken=")[1].split(";")[0];

        // when & then — 쿠키를 담아 로그아웃 요청
        mockMvc.perform(post("/api/v1/members/logout").cookie(new MockCookie("accessToken", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200"))
                .andExpect(jsonPath("$.msg").value("로그아웃 성공"))
                // Set-Cookie 헤더에서 accessToken이 만료(Max-Age=0)되어야 함
                .andExpect(cookie().maxAge("accessToken", 0));
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
