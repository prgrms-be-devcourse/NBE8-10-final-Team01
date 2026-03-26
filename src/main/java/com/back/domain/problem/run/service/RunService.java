package com.back.domain.problem.run.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.problem.run.dto.RunRequest;
import com.back.domain.problem.testcase.entity.TestCase;
import com.back.global.judge.event.RunRequestedEvent;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RunService {

    private final BattleRoomRepository battleRoomRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Map<String, String> run(RunRequest request, Long memberId) {

        // 1. BattleRoom 조회 + PLAYING 상태 검증 + 타이머 만료 검증
        BattleRoom room = battleRoomRepository
                .findById(request.roomId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 방입니다."));

        if (room.getStatus() != BattleRoomStatus.PLAYING) {
            throw new IllegalStateException("진행 중인 배틀이 아닙니다. 현재 상태: " + room.getStatus());
        }

        if (room.isExpired()) {
            throw new IllegalStateException("배틀 시간이 종료되었습니다.");
        }

        // 2. 샘플 테스트케이스만 필터링 (isSample=true)
        // new ArrayList<>()로 강제 초기화: PersistentBag을 트랜잭션 안에서 일반 List로 변환
        List<TestCase> sampleTestCases = new ArrayList<>(room.getProblem().getTestCases())
                .stream().filter(tc -> Boolean.TRUE.equals(tc.getIsSample())).toList();

        // 3. 이벤트 발행 → 트랜잭션 커밋 후 비동기 실행 → 즉시 RUNNING 응답
        eventPublisher.publishEvent(
                new RunRequestedEvent(room.getId(), memberId, request.code(), request.language(), sampleTestCases));

        return Map.of("message", "RUNNING");
    }
}
