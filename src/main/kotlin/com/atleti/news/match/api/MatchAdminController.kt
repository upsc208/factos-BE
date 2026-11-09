package com.atleti.news.match.api

import com.atleti.news.live.domain.LiveMatchMapper
import com.atleti.news.match.domain.FootballDataService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/* 관리자 경기 정보 동기화 */
@Tag(name = "Admin Match API", description = "관리자 경기 결과·일정 동기화 API")
@RestController
@RequestMapping("/api/admin/matches")
class MatchAdminController(
    private val footballDataService: FootballDataService,
    private val liveMatchMapper: LiveMatchMapper,
) {
    private val log = LoggerFactory.getLogger(MatchAdminController::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Operation(
        summary = "경기 정보 동기화",
        description = "6팀 직전 경기와 향후 일정을 뒤에서 받아오고 바로 202로 답합니다 (1분 남짓). " +
            "끝나면 라이브 골 알림 매핑도 이어서 만듭니다. 결과는 서버 로그에서 봅니다."
    )
    @PostMapping("/sync")
    fun sync(): ResponseEntity<Map<String, String>> {
        log.info("🔘 관리자가 경기 정보 동기화를 실행했습니다")
        // 6팀 × 경기 상세라 30초(스프링 비동기 요청 제한)를 넘긴다 — 요청에 묶어 두면 중간에 취소된다
        scope.launch {
            log.info("🔘 {}", footballDataService.syncUpdate())
            runCatching { liveMatchMapper.warmUp() }.onFailure { log.warn("⚠️ [Live] 매핑 실패: {}", it.message) }
        }
        return ResponseEntity.accepted().body(mapOf("message" to "동기화를 시작했습니다. 1분쯤 뒤 확인하세요."))
    }

    @Operation(
        summary = "라이브 골 알림 매핑 미리 만들기",
        description = "예정 경기마다 네이버 경기를 찾아 둡니다 (평소엔 07:10/19:10 자동). 일정 동기화 직후에 부르면 됩니다."
    )
    @PostMapping("/live-mapping")
    suspend fun liveMapping(): Map<String, Int> {
        log.info("🔘 관리자가 라이브 매핑을 실행했습니다")
        return mapOf("created" to liveMatchMapper.warmUp())
    }
}
