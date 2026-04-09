package com.back.domain.ranking.dashboard.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.member.member.entity.Member;
import com.back.domain.ranking.dashboard.dto.RankingDashboardResponse;
import com.back.domain.ranking.dashboard.service.RankingDashboardService;
import com.back.global.exception.ServiceException;
import com.back.global.rq.Rq;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/rankings")
// 로그인 사용자의 메인 홈 대시보드를 조회한다.
public class RankingDashboardController {

    private final RankingDashboardService rankingDashboardService;
    private final Rq rq;

    @GetMapping("/me/dashboard")
    public RankingDashboardResponse getMyDashboard() {
        Member actor = rq.getActor();
        if (actor == null || actor.getId() == null) {
            throw new ServiceException("MEMBER_401", "Login is required.");
        }

        return rankingDashboardService.getMyDashboard(actor.getId());
    }
}
