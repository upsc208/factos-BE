package com.atleti.news.match.api

import com.atleti.news.common.NotFoundException
import com.atleti.news.match.api.dto.RecentMatch
import com.atleti.news.match.api.dto.UpcomingMatchResponse
import com.atleti.news.match.api.dto.toResponse
import com.atleti.news.match.domain.FootballDataService
import com.atleti.news.news.domain.Club
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Match API", description = "Big6 팀별 직전 경기(상세·평점)와 다가올 경기")
@RestController
@RequestMapping("/api/matches/clubs/{clubId}")
class MatchController(
    private val footballDataService: FootballDataService,
    private val objectMapper: ObjectMapper
) {

    @Operation(
        summary = "직전 경기",
        description = "가장 최근 끝난 경기 1개: 스코어, 득점, 선발·교체, 카드, 통계, FotMob 선수 평점. " +
            "isHome은 요청한 팀 기준. 아직 없으면 204 (뒤에서 수집을 시작한다)"
    )
    @GetMapping("/recent")
    fun getRecentMatch(@PathVariable clubId: String): ResponseEntity<RecentMatch> {
        val club = club(clubId)
        val match = footballDataService.getRecentMatch(club) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(match.toResponse(objectMapper, club))
    }

    @Operation(summary = "다가올 경기", description = "킥오프 순 최대 3경기. matchId는 채팅 매치 방 키")
    @GetMapping("/upcoming")
    fun getUpcomingMatches(@PathVariable clubId: String): List<UpcomingMatchResponse> =
        footballDataService.getUpcomingMatches(club(clubId)).map { it.toResponse() }

    private fun club(clubId: String) = Club.fromId(clubId) ?: throw NotFoundException("알 수 없는 팀입니다: $clubId")
}
