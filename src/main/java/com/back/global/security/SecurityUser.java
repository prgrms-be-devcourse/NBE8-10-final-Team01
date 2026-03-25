package com.back.global.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import lombok.Getter;

// Spring Security가 인증 객체(principal)로 사용하는 유저 정보 클래스
// Member 엔티티를 직접 쓰지 않고 래퍼로 분리해 도메인 오염을 방지
public class SecurityUser implements UserDetails {

    // Rq.getActor()에서 Member.of() 호출 시 사용
    @Getter
    private final Long id; // 회원 PK

    private final String username; // 이메일 (Spring Security 식별자로 사용)
    // Rq.getActor()에서 Member.of() 호출 시 사용
    @Getter
    private final String name; // 닉네임

    private final String role; // 권한 (ex. ROLE_USER)

    // JWT claims 데이터로 생성 — DB 조회 없이 토큰에서 바로 만들 수 있음
    public SecurityUser(Long id, String username, String name, String role) {
        this.id = id;
        this.username = username;
        this.name = name;
        this.role = role;
    }

    // UserDetails 구현 — Spring Security가 유저 식별자로 사용 (이메일 반환)
    @Override
    public String getUsername() {
        return username;
    }

    // JWT 방식에서는 비밀번호를 SecurityContext에 저장할 필요 없음
    @Override
    public String getPassword() {
        return null;
    }

    // role 값을 GrantedAuthority로 변환해 Spring Security에 권한 정보를 제공
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role));
    }

    // 계정 만료 여부 — 현재는 만료 정책 없으므로 항상 유효
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    // 계정 잠금 여부 — 현재는 잠금 정책 없으므로 항상 잠금 해제
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    // 자격증명(비밀번호) 만료 여부 — JWT 방식이므로 항상 유효
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    // 계정 활성화 여부 — 현재는 비활성화 정책 없으므로 항상 활성
    @Override
    public boolean isEnabled() {
        return true;
    }
}
