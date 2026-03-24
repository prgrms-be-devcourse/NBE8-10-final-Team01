package com.back.global.jwt;

import java.io.IOException;
import java.util.Arrays;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.back.global.security.SecurityUser;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;

    // 모든 요청마다 한 번씩 실행되어 JWT 유효성을 검사하고 인증 정보를 등록
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Authorization 헤더에서 토큰 추출
        String token = resolveToken(request);

        // 토큰이 존재하고 유효한 경우에만 인증 처리
        if (token != null && jwtProvider.validateToken(token)) {
            // 토큰에서 claims(페이로드) 파싱
            Claims claims = jwtProvider.parseToken(token);

            // claims 데이터로 SecurityUser 생성 — DB 조회 없음
            SecurityUser securityUser = new SecurityUser(
                    Long.parseLong(claims.getSubject()), // subject = memberId
                    claims.get("email", String.class),
                    claims.get("nickname", String.class),
                    claims.get("role", String.class));

            // SecurityUser를 principal로 담은 인증 객체 생성
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(securityUser, null, securityUser.getAuthorities());
            // 요청 IP 등 추가 정보 설정
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            // SecurityContext에 인증 정보 저장 — 이후 rq.getActor()로 조회 가능
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        // 다음 필터로 요청 전달
        filterChain.doFilter(request, response);
    }

    // 쿠키에서 "accessToken" 값을 추출해 반환 — 없으면 null
    private String resolveToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(c -> "accessToken".equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
