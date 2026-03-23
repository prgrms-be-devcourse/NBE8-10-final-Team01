package com.back.domain.battle.battleroom.entity;

import java.time.Duration;
import java.time.LocalDateTime;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BattleRoomStatus status;

    private Integer maxPlayers;
    private LocalDateTime timerEnd;
    private LocalDateTime startedAt;

    /**
     * 방은 waiting 상태로 먼저 생성된다. (아무도 입장 안한 상태)
     * 참여자는 READY 상태 (입장 대기 중)
     * timerEnd는 null (모두 입장 후 세팅됨)
     */
    public static BattleRoom create(Problem problem, int maxPlayers) {
        BattleRoom room = new BattleRoom();
        room.problem = problem;
        room.maxPlayers = maxPlayers;
        room.status = BattleRoomStatus.WAITING;

        return room;
    }

    public void startBattle(Duration duration) {
        this.status = BattleRoomStatus.PLAYING;
        this.startedAt = LocalDateTime.now();
        this.timerEnd = this.startedAt.plus(duration);
    }

    public void finish() {
        this.status = BattleRoomStatus.FINISHED;
    }

    public boolean isExpired() {
        return timerEnd != null && LocalDateTime.now().isAfter(timerEnd);
    }
}
