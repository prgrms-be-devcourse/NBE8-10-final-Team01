package com.back.global.security;

import java.util.List;

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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.back.global.jwt.JwtAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;
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
                                "/ws/**",
                                "/actuator/health",
                                "/actuator/prometheus",
                                "/error",
                                // Swagger UI
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**")
                        .permitAll()
                        // 관리자 API는 ROLE_ADMIN만 접근 허용
                        .requestMatchers("/api/v1/admin/**")
                        .hasAuthority("ROLE_ADMIN")
                        // 그 외 모든 요청은 인증 필요 (POST /api/v1/ws/token 포함)
                        .anyRequest()
                        .authenticated())
                // UsernamePasswordAuthenticationFilter 앞에 JWT 필터 삽입
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 미인증 요청에 대해 기본 403 대신 401 반환
                // — 프론트의 fetchBackendWithReissue가 401을 감지해 토큰 재발급 후 재시도함
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"message\":\"로그인이 필요합니다.\"}");
                }));

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

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // 허용할 오리진 설정
        configuration.setAllowedOrigins(
                List.of("https://cdpn.io", "http://localhost:3000", "https://www.the-bracket.site"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));

        // 자격 증명 허용 설정
        configuration.setAllowCredentials(true);

        // 허용할 헤더 설정
        configuration.setAllowedHeaders(List.of("*"));

        // CORS 설정을 소스에 등록
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/ws/**", configuration);
        source.registerCorsConfiguration("/sse/**", configuration);

        return source;
    }
}
