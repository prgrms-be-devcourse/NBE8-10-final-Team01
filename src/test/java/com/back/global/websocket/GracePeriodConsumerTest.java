package com.back.global.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

class GracePeriodConsumerTest {

    private final BattleReconnectStore reconnectStore = mock(BattleReconnectStore.class);
    private final BattleParticipantRepository battleParticipantRepository = mock(BattleParticipantRepository.class);
    private final WebSocketMessagePublisher publisher = mock(WebSocketMessagePublisher.class);

    private final GracePeriodConsumer sut =
            new GracePeriodConsumer(reconnectStore, battleParticipantRepository, publisher);

    private static final Long MEMBER_ID = 10L;
    private static final Long ROOM_ID = 1L;

    @Test
    @DisplayName("grace period 만료 시 참여자가 ABANDONED이면 PARTICIPANT_LEFT를 브로드캐스트한다")
    void handle_ABANDONED참여자_PARTICIPANT_LEFT발행() {
        BattleRoom room = mock(BattleRoom.class);
        when(room.getId()).thenReturn(ROOM_ID);

        BattleParticipant participant = mock(BattleParticipant.class);
        when(participant.getBattleRoom()).thenReturn(room);

        when(battleParticipantRepository.findAbandonedParticipantByMemberId(
                        MEMBER_ID, BattleParticipantStatus.ABANDONED, BattleRoomStatus.PLAYING))
                .thenReturn(Optional.of(participant));

        sut.handle(MEMBER_ID);

        verify(publisher).publish(eq("/topic/room/" + ROOM_ID), any());
    }

    @Test
    @DisplayName("grace period 만료 시 참여자가 이미 재접속했으면 PARTICIPANT_LEFT를 발행하지 않는다")
    void handle_이미재접속한참여자_발행안함() {
        when(battleParticipantRepository.findAbandonedParticipantByMemberId(
                        MEMBER_ID, BattleParticipantStatus.ABANDONED, BattleRoomStatus.PLAYING))
                .thenReturn(Optional.empty());

        sut.handle(MEMBER_ID);

        verify(publisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("PARTICIPANT_LEFT 발행 시 올바른 roomId와 타입이 포함된다")
    void handle_발행메시지에_올바른roomId포함() {
        BattleRoom room = mock(BattleRoom.class);
        when(room.getId()).thenReturn(ROOM_ID);

        BattleParticipant participant = mock(BattleParticipant.class);
        when(participant.getBattleRoom()).thenReturn(room);

        when(battleParticipantRepository.findAbandonedParticipantByMemberId(
                        MEMBER_ID, BattleParticipantStatus.ABANDONED, BattleRoomStatus.PLAYING))
                .thenReturn(Optional.of(participant));

        sut.handle(MEMBER_ID);

        verify(publisher)
                .publish(
                        eq("/topic/room/" + ROOM_ID),
                        eq(java.util.Map.of("type", "PARTICIPANT_LEFT", "userId", MEMBER_ID)));
    }
}
