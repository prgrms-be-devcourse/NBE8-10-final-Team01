package com.back.domain.battle.battleroom.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.dto.BattleRoomStateResponse;
import com.back.domain.battle.battleroom.dto.CreateRoomRequest;
import com.back.domain.battle.battleroom.dto.CreateRoomResponse;
import com.back.domain.battle.battleroom.dto.JoinRoomResponse;
import com.back.domain.battle.battleroom.dto.OngoingRoomResponse;
import com.back.domain.battle.battleroom.dto.RoomResponse;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.repository.ProblemRepository;
import com.back.global.exception.ServiceException;
import com.back.global.websocket.BattleCodeStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class BattleRoomService {

    private final BattleRoomRepository battleRoomRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final ProblemRepository problemRepository;
    private final MemberRepository memberRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final BattleCodeStore battleCodeStore;

    @Transactional
    public CreateRoomResponse createRoom(CreateRoomRequest request) {

        // 1. problemId=1 로 문제 조회, 없으면 예외
        Problem problem = problemRepository
                .findById(request.problemId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 문제입니다."));

        // 2. BattleRoom 생성 후 저장
        BattleRoom room = BattleRoom.create(problem, request.maxPlayers());
        battleRoomRepository.save(room);

        // 3. 참여자 목록 조회
        List<Member> members = memberRepository.findAllById(request.participantIds());
        if (members.size() != request.participantIds().size()) {
            throw new IllegalArgumentException("존재하지 않는 참여자가 포함되어 있습니다.");
        }

        // 4. BattleParticipant 4개 생성 후 저장 (4명으로 고정이니까)
        // TODO: battleParticipantRepository::save를 효율적으로 바꿔야할지도 모름
        members.stream()
                .map(member -> BattleParticipant.create(room, member))
                .forEach(battleParticipantRepository::save);

        return CreateRoomResponse.from(room);
    }

    @Transactional
    public JoinRoomResponse joinRoom(Long roomId, Long memberId) {

        // 1. BattleRoom 조회 (비관적 락으로 동시 joinRoom 직렬화)
        BattleRoom room = battleRoomRepository
                .findByIdWithLock(roomId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 방입니다."));

        // WAITING(최초 입장) 또는 PLAYING(재입장) 상태만 허용
        if (room.getStatus() != BattleRoomStatus.WAITING && room.getStatus() != BattleRoomStatus.PLAYING) {
            throw new IllegalStateException("입장할 수 없는 상태의 방입니다. 현재 상태: " + room.getStatus());
        }

        // 2. Member 조회
        Member member =
                memberRepository.findById(memberId).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다."));

        // 3. BattleParticipant 조회
        BattleParticipant participant = battleParticipantRepository
                .findByBattleRoomAndMember(room, member)
                .orElseThrow(() -> new IllegalArgumentException("해당 방의 참여자가 아닙니다."));

        // 4. 참여자 상태에 따라 분기
        //    READY     → 최초 입장 (WAITING 상태 방)
        //    ABANDONED → 재입장 (PLAYING 상태 방)
        if (participant.getStatus() == BattleParticipantStatus.READY
                || participant.getStatus() == BattleParticipantStatus.ABANDONED) {
            participant.join();
            battleParticipantRepository.save(participant);
        } else {
            throw new IllegalStateException("입장할 수 없는 참여자 상태입니다. 현재 상태: " + participant.getStatus());
        }

        // 5. 최초 입장 시에만 allPlaying 체크 → 전원 PLAYING이면 배틀 시작
        //    재입장(PLAYING 방)은 이미 배틀이 시작된 상태이므로 체크하지 않음
        if (room.getStatus() == BattleRoomStatus.WAITING) {
            List<BattleParticipant> allParticipants = battleParticipantRepository.findByBattleRoom(room);
            boolean allPlaying =
                    allParticipants.stream().allMatch(p -> p.getStatus() == BattleParticipantStatus.PLAYING);

            if (allPlaying) {
                room.startBattle(Duration.ofMinutes(30));
                battleRoomRepository.save(room);
                String timerEnd = room.getTimerEnd().toString();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // 커밋 후이므로 예외를 전파해도 트랜잭션 롤백이 불가능하고,
                        // 클라이언트에게 500이 반환되어 DB는 성공했는데 실패로 인식하는 혼란을 유발함.
                        // WebSocket은 실시간 알림 역할이므로 전송 실패가 치명적이지 않아 예외를 삼키고 로그만 남김.
                        try {
                            messagingTemplate.convertAndSend(
                                    "/topic/room/" + roomId, Map.of("type", "BATTLE_STARTED", "timerEnd", timerEnd));
                        } catch (Exception e) {
                            log.error("BATTLE_STARTED WebSocket 전송 실패 roomId={}", roomId, e);
                        }
                    }
                });
            }
        }

        return JoinRoomResponse.from(room);
    }

    @Transactional(readOnly = true)
    public RoomResponse getRoomInfo(Long roomId) {

        BattleRoom room = battleRoomRepository
                .findByIdWithProblem(roomId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 방입니다."));

        List<BattleParticipant> participants = battleParticipantRepository.findByBattleRoom(room);
        return RoomResponse.from(room, participants);
    }

    @Transactional
    public void exitRoom(Long roomId, Long memberId) {

        BattleRoom room =
                battleRoomRepository.findById(roomId).orElseThrow(() -> new ServiceException("404-1", "존재하지 않는 방입니다."));

        if (room.getStatus() != BattleRoomStatus.PLAYING) {
            throw new ServiceException("400-1", "진행 중인 방이 아닙니다.");
        }

        Member member =
                memberRepository.findById(memberId).orElseThrow(() -> new ServiceException("404-1", "존재하지 않는 회원입니다."));

        BattleParticipant participant = battleParticipantRepository
                .findByBattleRoomAndMember(room, member)
                .orElseThrow(() -> new ServiceException("403-1", "해당 방의 참여자가 아닙니다."));

        if (participant.getStatus() != BattleParticipantStatus.PLAYING) {
            throw new ServiceException("400-1", "게임 중인 상태가 아닙니다. 현재 상태: " + participant.getStatus());
        }

        participant.quit();
        battleParticipantRepository.save(participant);
    }

    @Transactional(readOnly = true)
    public OngoingRoomResponse getOngoingRoom(Long memberId) {
        return battleParticipantRepository
                .findAbandonedParticipantByMemberId(
                        memberId, BattleParticipantStatus.ABANDONED, BattleRoomStatus.PLAYING)
                .map(p -> new OngoingRoomResponse(p.getBattleRoom().getId()))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public BattleRoomStateResponse getRoomState(Long roomId, Long memberId) {

        BattleRoom room = battleRoomRepository
                .findByIdWithProblem(roomId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 방입니다."));

        List<BattleParticipant> participants = battleParticipantRepository.findByBattleRoom(room);
        String myCode = battleCodeStore.get(roomId, memberId);
        return BattleRoomStateResponse.from(room, participants, myCode);
    }
}
