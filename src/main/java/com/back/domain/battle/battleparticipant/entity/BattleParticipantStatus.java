package com.back.domain.battle.battleparticipant.entity;

public enum BattleParticipantStatus {
    READY,
    PLAYING,
    SOLVED, // AC 판정으로 정상 완료
    ABANDONED, // 네트워크 이탈 (grace period 대상, 재입장 가능)
    TIMEOUT, // 시간 종료까지 PLAYING이었지만 미완료
    QUIT // 의도적 퇴장
}
