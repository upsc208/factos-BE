package com.atleti.news.news.api

import com.atleti.news.common.NotFoundException
import com.atleti.news.news.api.dto.BriefingResponse
import com.atleti.news.news.domain.Club
import com.atleti.news.news.domain.HeadlineService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Briefing API", description = "팀 브리핑 5줄")
@RestController
@RequestMapping("/api/headlines")
class HeadlineController(private val headlineService: HeadlineService) {

    @Operation(summary = "팀 브리핑", description = "그 팀 최신 소식 한 줄 요약, 최신순 최대 5줄. 6시간마다 갱신")
    @GetMapping("/clubs/{clubId}")
    fun briefing(@PathVariable clubId: String): BriefingResponse {
        val club = Club.fromId(clubId) ?: throw NotFoundException("알 수 없는 팀입니다: $clubId")
        val lines = headlineService.latest(club)
        return BriefingResponse(content = lines.map { it.content }, createdAt = lines.firstOrNull()?.createdAt)
    }
}
