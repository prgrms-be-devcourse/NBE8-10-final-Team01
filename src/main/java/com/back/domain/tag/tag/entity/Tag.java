package com.back.domain.tag.tag.entity;

import com.back.global.jpa.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tags")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tag extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tag_seq_gen")
    @SequenceGenerator(name = "tag_seq_gen", sequenceName = "tag_id_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    public static Tag create(String name) {
        Tag tag = new Tag();
        tag.name = name;
        return tag;
    }
}
