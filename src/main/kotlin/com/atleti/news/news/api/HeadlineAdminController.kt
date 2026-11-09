package com.atleti.news.news.api

import com.atleti.news.common.NotFoundException
import org.slf4j.LoggerFactory
import com.atleti.news.news.domain.Club
import com.atleti.news.news.domain.HeadlineService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin Headline API", description = "관리자 팀 브리핑 수동 실행 API")
@RestController
@RequestMapping("/api/admin/headline")
class HeadlineAdminController(private val headlineService: HeadlineService) {
    private val log = LoggerFactory.getLogger(HeadlineAdminController::class.java)

    @Operation(
        summary = "팀 브리핑 수동 생성",
        description = "직전 브리핑 이후 모인 기사로 새 줄을 만들고 팀마다 최신 5줄만 남깁니다. club을 빼면 6팀 모두"
    )
    @GetMapping("/trigger")
    suspend fun trigger(@RequestParam(required = false) club: String?): String {
        log.info("🔘 수동 브리핑 트리거 club={}", club ?: "all")
        if (club.isNullOrBlank()) {
            headlineService.generateAll()
            return "6팀 브리핑 실행 완료! 로그를 확인하세요."
        }
        val target = Club.fromId(club) ?: throw NotFoundException("알 수 없는 팀입니다: $club")
        return "${target.nameKo} 브리핑 ${headlineService.generateFor(target)}줄 추가"
    }
}
