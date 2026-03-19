package com.back.domain.member.member.entity;

import com.back.global.enums.Role;
import com.back.global.jpa.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    private Long score;
    private String tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;
}