package com.back.domain.tag.problemtagconnect.entity;

import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.tag.tag.entity.Tag;
import com.back.global.jpa.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "problem_tag_connect")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemTagConnect extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "problem_tag_seq_gen")
    @SequenceGenerator(name = "problem_tag_seq_gen", sequenceName = "problem_tag_id_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id")
    private Problem problem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id")
    private Tag tag;
}
