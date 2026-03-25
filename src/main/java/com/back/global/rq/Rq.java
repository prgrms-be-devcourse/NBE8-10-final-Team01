package com.back.global.rq;

import java.util.Arrays;
import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.back.domain.member.member.entity.Member;
import com.back.global.security.SecurityUser;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

// HTTP 요청/응답 컨텍스트를 다루는 유틸리티 빈
// 현재 로그인 유저 조회, 헤더/쿠키 조작, 리다이렉트 등을 한 곳에서 처리
@Component
@RequiredArgsConstructor
public class Rq {

    private final HttpServletRequest req;
    private final HttpServletResponse resp;

    // SecurityContext에서 현재 로그인 유저를 꺼내 경량 Member 객체로 반환
    // DB 조회 없이 JWT 토큰에 담긴 데이터만 사용 — 비용이 낮음
    // 로그인하지 않은 상태라면 null 반환
    public Member getActor() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .map(Authentication::getPrincipal)
                // principal이 SecurityUser 타입인 경우에만 처리
                .filter(principal -> principal instanceof SecurityUser)
                .map(principal -> (SecurityUser) principal)
                // SecurityUser의 id, email, nickname으로 경량 Member 생성
                .map(securityUser ->
                        Member.of(securityUser.getId(), securityUser.getUsername(), securityUser.getName()))
                .orElse(null);
    }

    // 요청 헤더 값 조회 — 없거나 빈 값이면 defaultValue 반환
    public String getHeader(String name, String defaultValue) {
        return Optional.ofNullable(req.getHeader(name))
                .filter(headerValue -> !headerValue.isBlank())
                .orElse(defaultValue);
    }

    // 응답 헤더 설정 — 빈 값이면 속성 제거, 값이 있으면 헤더에 추가
    public void setHeader(String name, String value) {
        if (value == null) value = "";
        if (value.isBlank()) {
            // 빈 값이면 요청 속성에서 제거
            req.removeAttribute(name);
        } else {
            resp.setHeader(name, value);
        }
    }

    // 쿠키 값 조회 — 해당 이름의 쿠키가 없거나 빈 값이면 defaultValue 반환
    public String getCookieValue(String name, String defaultValue) {
        return Optional.ofNullable(req.getCookies())
                .flatMap(cookies -> Arrays.stream(cookies)
                        .filter(cookie -> cookie.getName().equals(name))
                        .map(Cookie::getValue)
                        .filter(value -> !value.isBlank())
                        .findFirst())
                .orElse(defaultValue);
    }

    // 쿠키 설정 — 보안 속성(HttpOnly, Secure, SameSite) 적용
    // 빈 값이면 쿠키 삭제(MaxAge=0), 값이 있으면 1년 유지
    public void setCookie(String name, String value) {
        if (value == null) value = "";
        Cookie cookie = new Cookie(name, value);
        cookie.setPath("/"); // 모든 경로에서 접근 가능
        cookie.setHttpOnly(true); // JS에서 접근 불가 (XSS 방지)
        cookie.setDomain("localhost");
        cookie.setSecure(true); // HTTPS에서만 전송
        cookie.setAttribute("SameSite", "Strict"); // CSRF 방지
        if (value.isBlank()) cookie.setMaxAge(0); // 쿠키 즉시 삭제
        else cookie.setMaxAge(60 * 60 * 24 * 365); // 1년 유지
        resp.addCookie(cookie);
    }

    // 쿠키 삭제 — setCookie에 빈 값을 전달해 MaxAge=0으로 만료시킴
    public void deleteCookie(String name) {
        setCookie(name, null);
    }

    // 지정한 URL로 리다이렉트 — @SneakyThrows로 IOException 처리
    @SneakyThrows
    public void sendRedirect(String url) {
        resp.sendRedirect(url);
    }
}
