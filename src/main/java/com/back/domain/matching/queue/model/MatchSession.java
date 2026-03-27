package com.back.domain.matching.queue.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 하나의 매치 그룹 상태를 표현하는 모델
 *
 * 기존에는 userId -> roomId 형태로만 매칭 완료 상태를 저장했기 때문에
 * "이 사용자들이 하나의 매치였다"는 그룹 정보를 표현하기 어려웠다.
 *
 * 이번 단계에서는 MatchSession을 도입해서
 * 매칭 완료 상태를 유저 단위가 아니라 매치 단위로 저장한다.
 *
 * 현재는 프론트 변경 없이 기존 MATCHED 흐름을 유지하는 단계라
 * roomId가 이미 존재하는 MATCHED 상태만 다룬다.
 */
public record MatchSession(
        Long matchId, List<Long> participantIds, MatchSessionStatus status, Long roomId, LocalDateTime createdAt) {

    /**
     * 현재 단계에서는 4명 매칭이 끝나고 roomId까지 생성된 시점에만
     * MatchSession을 만들기 때문에 status=MATCHED, roomId!=null 을 기본 규칙으로 둔다.
     */
    public static MatchSession matched(Long matchId, List<Long> participantIds, Long roomId) {
        if (matchId == null) {
            throw new IllegalArgumentException("matchId는 필수입니다.");
        }

        if (participantIds == null || participantIds.isEmpty()) {
            throw new IllegalArgumentException("participantIds는 비어 있을 수 없습니다.");
        }

        if (roomId == null) {
            throw new IllegalArgumentException("MATCHED 상태의 roomId는 필수입니다.");
        }

        return new MatchSession(
                matchId, List.copyOf(participantIds), MatchSessionStatus.MATCHED, roomId, LocalDateTime.now());
    }
}
