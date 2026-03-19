package com.back.domain.battle.battleroom.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.problem.problem.entity.Problem;
import com.back.global.jpa.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "battle_rooms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BattleRoom extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "battle_room_seq_gen")
    @SequenceGenerator(name = "battle_room_seq_gen", sequenceName = "battle_room_id_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id")
    private Problem problem;

    private String status; // WAITING, PROGRESS, FINISHED
    private Integer maxPlayers;
    private LocalDateTime timerEnd;

    @OneToMany(mappedBy = "battleRoom")
    private List<BattleParticipant> participants = new ArrayList<>();
}