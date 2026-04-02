package com.back.domain.battle.battleroom.controller;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.back.domain.battle.battleroom.dto.OngoingRoomResponse;
import com.back.domain.battle.battleroom.service.BattleRoomService;
import com.back.domain.matching.queue.service.ReadyCheckService;
import com.back.domain.member.member.entity.Member;
import com.back.global.exception.ServiceException;
import com.back.global.globalExceptionHandler.GlobalExceptionHandler;
import com.back.global.rq.Rq;

class BattleRoomControllerExitOngoingTest {

    private final BattleRoomService battleRoomService = mock(BattleRoomService.class);
    private final ReadyCheckService readyCheckService = mock(ReadyCheckService.class);
    private final Rq rq = mock(Rq.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        BattleRoomController controller = new BattleRoomController(battleRoomService, readyCheckService, rq);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ──────────────────────────────────────────────
    // POST /{roomId}/exit
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("PLAYING 참여자가 exit 호출하면 204를 반환한다")
    void exitRoom_success() throws Exception {
        // given
        Long roomId = 1L;
        Member actor = Member.of(10L, "user@test.com", "user");

        when(rq.getActor()).thenReturn(actor);
        doNothing().when(battleRoomService).exitRoom(roomId, actor.getId());

        // when & then
        mockMvc.perform(post("/api/v1/battle/rooms/{roomId}/exit", roomId)).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("진행 중인 방이 아니면 exit 호출 시 400을 반환한다")
    void exitRoom_fail_whenRoomNotPlaying() throws Exception {
        // given
        Long roomId = 1L;
        Member actor = Member.of(10L, "user@test.com", "user");

        when(rq.getActor()).thenReturn(actor);
        doThrow(new ServiceException("400-1", "진행 중인 방이 아닙니다."))
                .when(battleRoomService)
                .exitRoom(roomId, actor.getId());

        // when & then
        mockMvc.perform(post("/api/v1/battle/rooms/{roomId}/exit", roomId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-1"))
                .andExpect(jsonPath("$.msg").value("진행 중인 방이 아닙니다."));
    }

    @Test
    @DisplayName("PLAYING 상태가 아닌 참여자가 exit 호출하면 400을 반환한다")
    void exitRoom_fail_whenParticipantNotPlaying() throws Exception {
        // given
        Long roomId = 1L;
        Member actor = Member.of(10L, "user@test.com", "user");

        when(rq.getActor()).thenReturn(actor);
        doThrow(new ServiceException("400-1", "게임 중인 상태가 아닙니다."))
                .when(battleRoomService)
                .exitRoom(roomId, actor.getId());

        // when & then
        mockMvc.perform(post("/api/v1/battle/rooms/{roomId}/exit", roomId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value("400-1"))
                .andExpect(jsonPath("$.msg").value("게임 중인 상태가 아닙니다."));
    }

    @Test
    @DisplayName("존재하지 않는 방에 exit 호출하면 404를 반환한다")
    void exitRoom_fail_whenRoomNotFound() throws Exception {
        // given
        Long roomId = 999L;
        Member actor = Member.of(10L, "user@test.com", "user");

        when(rq.getActor()).thenReturn(actor);
        doThrow(new ServiceException("404-1", "존재하지 않는 방입니다."))
                .when(battleRoomService)
                .exitRoom(roomId, actor.getId());

        // when & then
        mockMvc.perform(post("/api/v1/battle/rooms/{roomId}/exit", roomId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultCode").value("404-1"))
                .andExpect(jsonPath("$.msg").value("존재하지 않는 방입니다."));
    }

    // ──────────────────────────────────────────────
    // GET /ongoing
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("ABANDONED 상태로 진행 중인 방이 있으면 roomId를 반환한다")
    void getOngoingRoom_whenExists() throws Exception {
        // given
        Member actor = Member.of(10L, "user@test.com", "user");

        when(rq.getActor()).thenReturn(actor);
        when(battleRoomService.getOngoingRoom(actor.getId())).thenReturn(new OngoingRoomResponse(42L));

        // when & then
        mockMvc.perform(get("/api/v1/battle/rooms/ongoing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(42));
    }

    @Test
    @DisplayName("진행 중인 방이 없으면 null을 반환한다")
    void getOngoingRoom_whenNotExists() throws Exception {
        // given
        Member actor = Member.of(10L, "user@test.com", "user");

        when(rq.getActor()).thenReturn(actor);
        when(battleRoomService.getOngoingRoom(actor.getId())).thenReturn(null);

        // when & then
        mockMvc.perform(get("/api/v1/battle/rooms/ongoing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist());
    }
}
