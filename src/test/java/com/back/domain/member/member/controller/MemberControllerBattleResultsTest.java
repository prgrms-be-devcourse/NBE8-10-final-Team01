package com.back.domain.member.member.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.back.domain.battle.result.dto.MyBattleResultsResponse;
import com.back.domain.battle.result.service.BattleResultService;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.service.MemberRatingProgressService;
import com.back.domain.member.member.service.MemberService;
import com.back.domain.member.member.service.MemberSolveHeatmapService;
import com.back.global.globalExceptionHandler.GlobalExceptionHandler;
import com.back.global.jwt.RefreshTokenService;
import com.back.global.rq.Rq;

class MemberControllerBattleResultsTest {

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
    @DisplayName("로그인 사용자는 RsData 래핑된 내 전적 조회 응답을 받는다")
    void getMyBattleResults_success() throws Exception {
        // given
        Member actor = Member.of(1L, "test@example.com", "tester");

        MyBattleResultsResponse.MyBattleResultItem item = new MyBattleResultsResponse.MyBattleResultItem(
                101L,
                5L,
                "Two Sum",
                2,
                70L,
                true,
                LocalDateTime.of(2026, 3, 24, 20, 15, 20),
                LocalDateTime.of(2026, 3, 24, 20, 0, 0));

        MyBattleResultsResponse response =
                new MyBattleResultsResponse(List.of(item), new MyBattleResultsResponse.PageInfo(0, 20, 1, 1, false));

        when(rq.getActor()).thenReturn(actor);
        when(battleResultService.getMyBattleResults(1L, 0, 20)).thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/v1/members/me/battle-results")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("200"))
                .andExpect(jsonPath("$.msg").value("내 전적 조회 성공"))
                .andExpect(jsonPath("$.data.battleResults[0].roomId").value(101))
                .andExpect(jsonPath("$.data.battleResults[0].problemId").value(5))
                .andExpect(jsonPath("$.data.battleResults[0].problemTitle").value("Two Sum"))
                .andExpect(jsonPath("$.data.battleResults[0].finalRank").value(2))
                .andExpect(jsonPath("$.data.battleResults[0].scoreDelta").value(70))
                .andExpect(jsonPath("$.data.battleResults[0].solved").value(true))
                .andExpect(jsonPath("$.data.pageInfo.page").value(0))
                .andExpect(jsonPath("$.data.pageInfo.size").value(20))
                .andExpect(jsonPath("$.data.pageInfo.totalElements").value(1))
                .andExpect(jsonPath("$.data.pageInfo.totalPages").value(1))
                .andExpect(jsonPath("$.data.pageInfo.hasNext").value(false));
    }

    @Test
    @DisplayName("비로그인 사용자는 401 응답을 받는다")
    void getMyBattleResults_fail_whenNotLoggedIn() throws Exception {
        // given
        when(rq.getActor()).thenReturn(null);

        // when & then
        mockMvc.perform(get("/api/v1/members/me/battle-results"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultCode").value("MEMBER_401"))
                .andExpect(jsonPath("$.msg").value("로그인이 필요합니다."));
    }
}
