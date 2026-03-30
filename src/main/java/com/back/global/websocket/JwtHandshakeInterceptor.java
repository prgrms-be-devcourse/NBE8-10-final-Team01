package com.back.global.websocket;

import java.util.Arrays;
import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.back.global.jwt.JwtProvider;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtProvider jwtProvider;

    /**
     * HTTP 핸드셰이크 시점에 JWT 쿠키를 읽어서 memberId를 WebSocket 세션 속성에 저장.
     * 이후 STOMP CONNECT 시 ChannelInterceptor에서 세션 속성의 memberId를 꺼내 sessionRegistry에 등록.
     */
    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return true;
        }

        Cookie[] cookies = servletRequest.getServletRequest().getCookies();
        if (cookies == null) {
            log.debug("WebSocket 핸드셰이크 - 쿠키 없음");
            return true;
        }

        Arrays.stream(cookies)
                .filter(c -> "accessToken".equals(c.getName()))
                .findFirst()
                .ifPresent(cookie -> {
                    String token = cookie.getValue();
                    log.debug(
                            "WebSocket 핸드셰이크 - accessToken 쿠키 발견, token 앞 20자={}",
                            token.length() > 20 ? token.substring(0, 20) : token);
                    if (jwtProvider.validateToken(token)) {
                        Claims claims = jwtProvider.parseToken(token);
                        Long memberId = Long.parseLong(claims.getSubject());
                        attributes.put("memberId", memberId);
                        log.info("WebSocket 핸드셰이크 - memberId={} 세션 속성 저장 성공", memberId);
                    } else {
                        log.warn("WebSocket 핸드셰이크 - JWT 유효하지 않음 (만료 또는 secret 불일치)");
                    }
                });

        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {}
}
