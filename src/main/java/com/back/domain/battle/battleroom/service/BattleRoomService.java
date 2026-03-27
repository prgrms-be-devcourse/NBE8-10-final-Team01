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
import com.back.domain.battle.battleroom.dto.CreateRoomRequest;
import com.back.domain.battle.battleroom.dto.CreateRoomResponse;
import com.back.domain.battle.battleroom.dto.JoinRoomResponse;
import com.back.domain.battle.battleroom.dto.RoomResponse;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.repository.ProblemRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BattleRoomService {

    private final BattleRoomRepository battleRoomRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final ProblemRepository problemRepository;
    private final MemberRepository memberRepository;
    private final SimpMessagingTemplate messagingTemplate;

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

        // 1. BattleRoom 조회 + WAITING 상태 검증
        BattleRoom room =
                battleRoomRepository.findById(roomId).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 방입니다."));

        if (room.getStatus() != BattleRoomStatus.WAITING) {
            throw new IllegalStateException("입장할 수 없는 상태의 방입니다. 현재 상태: " + room.getStatus());
        }

        // 2. Member 조회
        Member member =
                memberRepository.findById(memberId).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다."));

        // 3. BattleParticipant 조회
        BattleParticipant participant = battleParticipantRepository
                .findByBattleRoomAndMember(room, member)
                .orElseThrow(() -> new IllegalArgumentException("해당 방의 참여자가 아닙니다."));

        // 4. READY → PLAYING 상태 바꾸기
        participant.join();

        // 5. 모든 참여자가 PLAYING이면 배틀 시작
        List<BattleParticipant> allParticipants = battleParticipantRepository.findByBattleRoom(room);
        boolean allPlaying = allParticipants.stream().allMatch(p -> p.getStatus() == BattleParticipantStatus.PLAYING);

        if (allPlaying) {
            room.startBattle(Duration.ofMinutes(30));
            String timerEnd = room.getTimerEnd().toString();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    messagingTemplate.convertAndSend(
                            "/topic/room/" + roomId, Map.of("type", "BATTLE_STARTED", "timerEnd", timerEnd));
                }
            });
        }

        return JoinRoomResponse.from(room);
    }

    @Transactional(readOnly = true)
    public RoomResponse getRoomInfo(Long roomId) {

        BattleRoom room =
                battleRoomRepository.findById(roomId).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 방입니다."));

        List<BattleParticipant> participants = battleParticipantRepository.findByBattleRoom(room);
        return RoomResponse.from(room, participants);
    }
}
