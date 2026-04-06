package com.back.domain.battle.result.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.back.domain.battle.result.dto.ActiveRoomResponse;
import com.back.domain.battle.result.service.BattleResultService;
import com.back.domain.member.member.entity.Member;
import com.back.global.globalExceptionHandler.GlobalExceptionHandler;
import com.back.global.rq.Rq;

class BattleResultControllerActiveRoomTest {

    private final BattleResultService battleResultService = mock(BattleResultService.class);
    private final Rq rq = mock(Rq.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        BattleResultController controller = new BattleResultController(battleResultService, rq);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("참여 중인 방이 있으면 roomId를 반환한다")
    void getActiveRoom_whenExists() throws Exception {
        // given
        Member actor = Member.of(10L, "user@test.com", "user");

        when(rq.getActor()).thenReturn(actor);
        when(battleResultService.getActiveRoom(actor.getId())).thenReturn(new ActiveRoomResponse(42L));

        // when & then
        mockMvc.perform(get("/api/v1/battle/rooms/me/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(42));
    }

    @Test
    @DisplayName("참여 중인 방이 없으면 null을 반환한다")
    void getActiveRoom_whenNotExists() throws Exception {
        // given
        Member actor = Member.of(10L, "user@test.com", "user");

        when(rq.getActor()).thenReturn(actor);
        when(battleResultService.getActiveRoom(actor.getId())).thenReturn(null);

        // when & then
        mockMvc.perform(get("/api/v1/battle/rooms/me/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist());
    }
}
