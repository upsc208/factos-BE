package com.atleti.news.rating.api

import com.atleti.news.common.ClientIp
import com.atleti.news.common.NotFoundException
import com.atleti.news.news.domain.Club
import com.atleti.news.rating.api.dto.PlayerRatingRequest
import com.atleti.news.rating.api.dto.PlayerRatingResponse
import com.atleti.news.rating.api.dto.toResponse
import com.atleti.news.rating.domain.PlayerRatingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.*

@Tag(name = "Player Rating API", description = "경기 선수 팬 평점 (로그인 없이 IP 기반, 0~5점) + FotMob 평점")
@RestController
@RequestMapping("/api/matches/{matchId}/ratings")
class PlayerRatingController(
    private val playerRatingService: PlayerRatingService
) {

    @Operation(
        summary = "선수 평점 목록 조회",
        description = "그 경기에서 club 팀으로 뛴 선수별 팬 평균 평점, 투표수, 요청 IP가 준 점수(myScore), FotMob 평점을 반환합니다."
    )
    @GetMapping
    fun getRatings(@PathVariable matchId: String, @RequestParam club: String, request: HttpServletRequest): List<PlayerRatingResponse> =
        playerRatingService.getRatings(matchId, club(club), ClientIp.of(request)).map { it.toResponse() }

    @Operation(
        summary = "선수 평점 등록/수정",
        description = "0~5점으로 선수 평점을 남깁니다. 같은 IP가 다시 보내면 점수가 수정됩니다. 응답으로 갱신된 평균/투표수를 즉시 돌려줍니다."
    )
    @PostMapping
    fun submitRating(
        @PathVariable matchId: String,
        @RequestBody req: PlayerRatingRequest,
        request: HttpServletRequest
    ): PlayerRatingResponse =
        playerRatingService.submitRating(matchId, club(req.club), req.playerName, req.score, ClientIp.of(request)).toResponse()

    private fun club(id: String) = Club.fromId(id) ?: throw NotFoundException("알 수 없는 팀입니다: $id")
}
