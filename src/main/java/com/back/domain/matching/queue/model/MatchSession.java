package com.back.domain.matching.queue.model;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 하나의 매치 그룹 상태를 표현하는 모델
 *
 * 기존에는 userId -> roomId 형태로만 매칭 완료 상태를 저장했기 때문에
 * "이 사용자들이 하나의 매치였다"는 그룹 정보를 표현하기 어려웠다.
 *
 * 이번 단계에서는 MatchSession을 도입해서
 * 매칭 완료 상태를 유저 단위가 아니라 매치 단위로 저장한다.
 *
 * 현재는 v2 ready-check 흐름을 기준으로
 * queueKey, deadline, participantDecisions 같은 필드를 확장해 두었다.
 * 여기서 participantDecisions를 단일 원본으로 두는 이유는
 * acceptedUserIds 같은 파생 상태를 따로 저장하지 않고도
 * acceptedCount / acceptedByMe를 항상 같은 기준에서 계산하기 위해서다.
 */
public record MatchSession(
        Long matchId,
        QueueKey queueKey,
        // 이 매치에 원래 묶인 참가자 원본 목록이다.
        // ready-check 진행 중에도 이 목록 자체는 바뀌지 않는다.
        List<Long> participantIds,
        // ready-check의 단일 상태 원본이다.
        // 누가 ACCEPTED / PENDING / DECLINED 인지는 여기만 보면 된다.
        Map<Long, ReadyDecision> participantDecisions,
        MatchSessionStatus status,
        Long roomId,
        LocalDateTime deadline,
        LocalDateTime createdAt) {

    public MatchSession {
        if (matchId == null) {
            throw new IllegalArgumentException("matchId는 필수입니다.");
        }

        if (participantIds == null || participantIds.isEmpty()) {
            throw new IllegalArgumentException("participantIds는 비어 있을 수 없습니다.");
        }

        if (participantDecisions == null || participantDecisions.isEmpty()) {
            throw new IllegalArgumentException("participantDecisions는 비어 있을 수 없습니다.");
        }

        // record로 받은 컬렉션이 바깥에서 수정되지 않도록 방어 복사한다.
        participantIds = List.copyOf(participantIds);
        participantDecisions = Map.copyOf(participantDecisions);
    }

    /**
     * v2 ready-check 세션 팩토리
     *
     * 4명이 모인 시점에는 아직 방을 만들지 않고,
     * 먼저 ACCEPT_PENDING 세션을 만든 뒤 각 참가자의 수락 여부를 기다린다.
     */
    public static MatchSession acceptPending(
            Long matchId, QueueKey queueKey, List<Long> participantIds, LocalDateTime deadline) {
        if (deadline == null) {
            throw new IllegalArgumentException("ready-check 세션은 deadline이 필요합니다.");
        }

        Map<Long, ReadyDecision> participantDecisions = new LinkedHashMap<>();
        for (Long participantId : participantIds) {
            // ready-check를 막 시작한 시점이므로 모든 참가자의 초기 상태는 PENDING이다.
            participantDecisions.put(participantId, ReadyDecision.PENDING);
        }

        return new MatchSession(
                matchId,
                queueKey,
                participantIds,
                participantDecisions,
                MatchSessionStatus.ACCEPT_PENDING,
                null,
                deadline,
                LocalDateTime.now());
    }

    /**
     * ready-check는 participantDecisions만 바꾸면 acceptedCount 같은 파생 값을 다시 계산할 수 있으므로,
     * 유저 단위 응답 상태 변경은 이 메서드 하나로 모은다.
     */
    public MatchSession withDecision(Long userId, ReadyDecision decision) {
        if (!participantDecisions.containsKey(userId)) {
            throw new IllegalStateException("매치 참가자가 아닌 사용자는 decision을 변경할 수 없습니다.");
        }

        // 기존 세션을 직접 수정하지 않고 새 세션을 만들어 불변성을 유지한다.
        Map<Long, ReadyDecision> updatedDecisions = new LinkedHashMap<>(participantDecisions);
        updatedDecisions.put(userId, decision);

        return new MatchSession(
                matchId, queueKey, participantIds, updatedDecisions, status, roomId, deadline, createdAt);
    }

    /**
     * ready-check 흐름에서는 전원 수락이 끝난 뒤에만 roomId를 세션에 연결한다.
     */
    public MatchSession roomReady(Long roomId) {
        if (roomId == null) {
            throw new IllegalArgumentException("ROOM_READY 상태의 roomId는 필수입니다.");
        }

        // ready-check가 끝난 뒤에만 roomId를 세션에 연결한다.
        return new MatchSession(
                matchId,
                queueKey,
                participantIds,
                participantDecisions,
                MatchSessionStatus.ROOM_READY,
                roomId,
                deadline,
                createdAt);
    }

    public MatchSession expired() {
        // 누가 어디까지 수락했는지는 프론트가 보여줘야 하므로 decision 정보는 유지한다.
        return new MatchSession(
                matchId,
                queueKey,
                participantIds,
                participantDecisions,
                MatchSessionStatus.EXPIRED,
                roomId,
                deadline,
                createdAt);
    }

    public MatchSession cancelled() {
        // CANCELLED 역시 종료 이유를 설명하기 위해 기존 decision들을 유지한다.
        return new MatchSession(
                matchId,
                queueKey,
                participantIds,
                participantDecisions,
                MatchSessionStatus.CANCELLED,
                roomId,
                deadline,
                createdAt);
    }

    public boolean hasParticipant(Long userId) {
        return participantDecisions.containsKey(userId);
    }

    public ReadyDecision decisionOf(Long userId) {
        // 안전하게 기본값을 PENDING으로 두면 일부 누락이 있어도 계산 로직이 깨지지 않는다.
        return participantDecisions.getOrDefault(userId, ReadyDecision.PENDING);
    }

    public int acceptedCount() {
        // acceptedUserIds를 따로 저장하지 않고 필요할 때마다 계산한다.
        return (int) participantDecisions.values().stream()
                .filter(decision -> decision == ReadyDecision.ACCEPTED)
                .count();
    }

    public boolean isAcceptedBy(Long userId) {
        return decisionOf(userId) == ReadyDecision.ACCEPTED;
    }

    public boolean isAllAccepted() {
        // ready-check의 단일 원본은 participantDecisions 이므로
        // 전원 수락 여부도 이 decision 값들만 보고 판단한다.
        return participantDecisions.values().stream().allMatch(decision -> decision == ReadyDecision.ACCEPTED);
    }

    public boolean isExpiredAt(LocalDateTime now) {
        // ACCEPT_PENDING 상태에서만 deadline이 의미 있다.
        return status == MatchSessionStatus.ACCEPT_PENDING && deadline != null && !deadline.isAfter(now);
    }

    public boolean hasDeclinedParticipant() {
        return participantDecisions.values().stream().anyMatch(decision -> decision == ReadyDecision.DECLINED);
    }
}
