package com.back.global.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.back.global.jwt.JwtAuthenticationFilter;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomUserDetailsService customUserDetailsService;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable) // REST API이므로 CSRF 비활성화
                // 세션을 사용하지 않는 Stateless 방식 설정
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 회원가입, 로그인은 인증 없이 접근 허용
                        .requestMatchers(
                                "/api/v1/members/join",
                                "/api/v1/members/login",
                                // 로그아웃은 인증 없이도 호출 가능해야 함
                                "/api/v1/members/logout",
                                // refreshToken으로 accessToken 재발급 — 인증 없이 접근 허용
                                "/api/v1/auth/reissue",
                                // WebSocket 핸드셰이크는 HTTP 레벨에서 허용:
                                // - 로컬 환경: JWT 쿠키가 자동 전송되어 JwtAuthenticationFilter가 인증 처리
                                // - 배포 환경: cross-origin으로 쿠키 자동 전송 불가 → 쿠키 없이 핸드셰이크 허용
                                // 실제 인증 검증은 STOMP CONNECT 단계에서 ChannelInterceptor가 담당
                                // (쿠키 기반 Principal 전파 또는 X-WS-Token 1회용 토큰 검증)
                                "/ws/**")
                        .permitAll()
                        // 그 외 모든 요청은 인증 필요 (POST /api/v1/ws/token 포함)
                        .anyRequest()
                        .authenticated())
                // UsernamePasswordAuthenticationFilter 앞에 JWT 필터 삽입
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // OAuth2 도입 시 Spring Security가 사용할 인증 프로바이더
    // UserDetailsService와 PasswordEncoder를 연결해 인증 처리를 위임
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(customUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    // 비밀번호 암호화에 BCrypt 알고리즘 사용
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
