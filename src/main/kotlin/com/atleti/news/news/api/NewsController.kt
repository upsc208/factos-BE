package com.atleti.news.news.api

import com.atleti.news.common.ClientIp
import com.atleti.news.common.NotFoundException
import com.atleti.news.news.api.dto.ClubNewsResponse
import com.atleti.news.news.api.dto.NewsDetailResponse
import com.atleti.news.news.api.dto.ClubSourceResponse
import com.atleti.news.news.domain.Club
import com.atleti.news.news.domain.ClubJournalists
import com.atleti.news.news.domain.ClubNewsPipeline
import com.atleti.news.news.domain.NewsArticleRepository
import com.atleti.news.news.domain.ViewCountService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.web.bind.annotation.*
import jakarta.servlet.http.HttpServletRequest

@Tag(name = "News API", description = "Big6 팀별 뉴스 조회 API")
@RestController
@RequestMapping("/api/news")
class NewsController(
    private val newsArticleRepository: NewsArticleRepository,
    private val viewCountService: ViewCountService
) {

    @Operation(
        summary = "팀별 뉴스 목록 (Big6)",
        description = "clubId는 arsenal, chelsea, liverpool, mancity, manutd, tottenham 중 하나. 최신순. " +
            "번역 전 기사(translationStatus=PENDING)는 titleKo가 null이고 keySentences가 영어 원문이다."
    )
    @GetMapping("/clubs/{clubId}")
    fun getClubNews(
        @PathVariable clubId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) sources: List<String>?,
        @RequestParam(required = false) search: String?,
    ): Page<ClubNewsResponse> {
        val club = Club.fromId(clubId) ?: throw NotFoundException("알 수 없는 팀입니다: $clubId")
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE), Sort.by("publishedAt").descending())
        return newsArticleRepository.findAllByClub(
            club,
            sources?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.ifEmpty { null },
            search?.trim()?.ifEmpty { null },
            pageable,
        ).map { ClubNewsResponse.of(it) }
    }

    @Operation(
        summary = "팀별 출처 목록 (Big6)",
        description = "출처 필터에 쓰는 목록. internalId를 /clubs/{clubId}?sources= 에 그대로 넘긴다 (대소문자 무관)."
    )
    @GetMapping("/clubs/{clubId}/sources")
    fun getClubSources(@PathVariable clubId: String): List<ClubSourceResponse> {
        val club = Club.fromId(clubId) ?: throw NotFoundException("알 수 없는 팀입니다: $clubId")
        return ClubSourceResponse.MEDIA + ClubJournalists.handlesFor(club).map(ClubSourceResponse::journalist)
    }

    @Operation(summary = "뉴스 상세 조회 (전체 포함)", description = "특정 기사의 상세 정보(이미지, 본문 포함)를 가져옵니다.")
    @GetMapping("/{id}")
    fun getNewsDetail(@PathVariable id: Long, request: HttpServletRequest): NewsDetailResponse {
        val article = newsArticleRepository.findById(id)
            .orElseThrow { NotFoundException("해당 기사를 찾을 수 없습니다. ID: $id") }

        val counted = viewCountService.countView(id, ClientIp.of(request))
        val viewCount = article.viewCount + if (counted) 1 else 0

        return NewsDetailResponse(
            id = article.id!!,
            titleKo = article.titleKo ?: article.titleEn ?: "제목 없음", // Big6 기사는 번역 전이면 영어 제목
            summaryKo = article.summaryKo ?: "", 
            contentKo = article.contentKo ?: "본문 내용이 없습니다.",
            imageUrl = article.imageUrl,
            sourceName = article.sourceName,
            authorName = article.authorName ?: article.sourceName,
            originalUrl = article.originalUrl,
            publishedAt = article.publishedAt,
            articleType = article.articleType,
            category = article.category ?: "[기타]",
            viewCount = viewCount,
            titleEn = article.titleEn,
            keySentencesEn = article.keySentencesEn?.let { ClubNewsPipeline.splitSentences(it) },
            translationStatus = article.translationStatus,
        )
    }

    companion object {
        private const val MAX_PAGE_SIZE = 100
    }
}
