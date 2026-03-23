package com.back.domain.member.member.entity;

import com.back.global.enums.Role;
import com.back.global.jpa.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Locale;

import org.springframework.util.Assert;

@Entity
@Table(name = "members")
@Getter // 데이터를 꺼내오기 위해 필수!
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class Member extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "member_seq_gen")
    @SequenceGenerator(name = "member_seq_gen", sequenceName = "member_id_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    // 암호화된 비밀번호
    @Column(name = "password", nullable = false, length = 100)
    private String password;

    private Long score;
    private String tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Builder(access = AccessLevel.PRIVATE)
    private Member(String nickname, String email, String password, Role role) {
        // 서비스 레이어 외의 호출에 대비한 최소한의 방어적 검증 (NPE 방지)
        Assert.hasText(nickname, "닉네임은 필수입니다.");
        Assert.hasText(email, "이메일은 필수입니다.");
        Assert.hasText(password, "비밀번호는 필수입니다.");

        this.nickname = nickname;
        this.email = email;
        this.password = password;
        this.role = (role != null) ? role : Role.USER;
        this.score = 0L; // 초기값 설정
    }
    public static Member createUser(String nickname, String email, String encodedPassword) {
        // 이메일 정규화 (앞뒤 공백 제거 및 소문자 변환)
        String normalizedEmail = (email != null)
                ? email.trim().toLowerCase(Locale.ROOT)
                : null;

        return Member.builder()
                .nickname(nickname)
                .email(normalizedEmail)
                .password(encodedPassword)
                .role(Role.USER)
                .build();
    }
}
