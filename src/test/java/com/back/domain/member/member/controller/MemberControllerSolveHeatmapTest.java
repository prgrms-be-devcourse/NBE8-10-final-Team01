package com.back.domain.member.member.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.back.domain.battle.result.service.BattleResultService;
import com.back.domain.member.member.dto.SolveHeatmapResponse;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.service.MemberRatingProgressService;
import com.back.domain.member.member.service.MemberService;
import com.back.domain.member.member.service.MemberSolveHeatmapService;
import com.back.global.globalExceptionHandler.GlobalExceptionHandler;
import com.back.global.jwt.RefreshTokenService;
import com.back.global.rq.Rq;
import com.back.global.rsData.RsData;

class MemberControllerSolveHeatmapTest {

    private final MemberService memberService = mock(MemberService.class);
    private final MemberRatingProgressService memberRatingProgressService = mock(MemberRatingProgressService.class);
    private final MemberSolveHeatmapService memberSolveHeatmapService = mock(MemberSolveHeatmapService.class);
    private final BattleResultService battleResultService = mock(BattleResultService.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final Rq rq = mock(Rq.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MemberController controller = new MemberController(
                memberService,
                memberRatingProgressService,
                memberSolveHeatmapService,
                battleResultService,
                refreshTokenService,
                rq);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Solve heatmap includes the requested year response for logged-in users.")
    void getMySolveHeatmap_success() throws Exception {
        Member actor = Member.of(7L, "heatmap@example.com", "heatmap-user");

        SolveHeatmapResponse response = new SolveHeatmapResponse(
                2026,
                List.of(2026, 2025),
                2,
                2,
                List.of(new SolveHeatmapResponse.MonthLabel(4, "Apr", 13)),
                List.of(new SolveHeatmapResponse.Week(
                        "2026-04-05", List.of(new SolveHeatmapResponse.Day("2026-04-09", 1, 1, 2, 2, true, false)))));

        when(rq.getActor()).thenReturn(actor);
        when(memberSolveHeatmapService.getMySolveHeatmap(7L, 2026))
                .thenReturn(RsData.of("200", "Solve heatmap fetched successfully.", response));

        mockMvc.perform(get("/api/v1/members/me/solve-heatmap").param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200"))
                .andExpect(jsonPath("$.data.year").value(2026))
                .andExpect(jsonPath("$.data.availableYears[0]").value(2026))
                .andExpect(jsonPath("$.data.weeks[0].days[0].soloSolvedCount").value(1))
                .andExpect(jsonPath("$.data.weeks[0].days[0].battleSolvedCount").value(1))
                .andExpect(jsonPath("$.data.weeks[0].days[0].totalSolvedCount").value(2));
    }

    @Test
    @DisplayName("Anonymous users receive 401 for solve heatmap.")
    void getMySolveHeatmap_fail_whenNotLoggedIn() throws Exception {
        when(rq.getActor()).thenReturn(null);

        mockMvc.perform(get("/api/v1/members/me/solve-heatmap"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("MEMBER_401"));
    }
}
