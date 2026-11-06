package com.atleti.news.member.bookmark

import com.atleti.news.common.NotFoundException
import com.atleti.news.member.auth.MemberAuth
import com.atleti.news.news.api.dto.ClubNewsResponse
import com.atleti.news.news.domain.NewsArticleRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.web.bind.annotation.*

data class BookmarkStatus(val articleId: Long, val bookmarked: Boolean)

@Tag(name = "Bookmark API", description = "기사 즐겨찾기 (로그인 필요)")
@RestController
@RequestMapping("/api/me/bookmarks")
class BookmarkController(
    private val bookmarkRepository: BookmarkRepository,
    private val newsArticleRepository: NewsArticleRepository,
    private val memberAuth: MemberAuth,
) {
    @Operation(summary = "내 즐겨찾기 목록", description = "최근에 담은 순")
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        request: HttpServletRequest,
    ): Page<ClubNewsResponse> {
        val member = memberAuth.requireCompleted(request)
        return bookmarkRepository.findArticles(member.id!!, PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100)))
            .map { ClubNewsResponse.of(it) }
    }

    @Operation(summary = "이 기사를 즐겨찾기 했는지")
    @GetMapping("/{articleId}")
    fun status(@PathVariable articleId: Long, request: HttpServletRequest): BookmarkStatus {
        val member = memberAuth.requireCompleted(request)
        return BookmarkStatus(articleId, bookmarkRepository.existsByMemberIdAndArticleId(member.id!!, articleId))
    }

    @Operation(summary = "즐겨찾기 추가", description = "이미 있으면 그대로 (여러 번 눌러도 하나)")
    @PutMapping("/{articleId}")
    fun add(@PathVariable articleId: Long, request: HttpServletRequest): BookmarkStatus {
        val member = memberAuth.requireCompleted(request)
        if (!newsArticleRepository.existsById(articleId)) throw NotFoundException("해당 기사를 찾을 수 없습니다. ID: $articleId")
        if (!bookmarkRepository.existsByMemberIdAndArticleId(member.id!!, articleId)) {
            try {
                bookmarkRepository.save(Bookmark(memberId = member.id!!, articleId = articleId))
            } catch (e: DataIntegrityViolationException) {
                // 두 번 빠르게 눌러 동시에 들어온 경우 — 유니크 제약이 하나만 남겼다. 결과는 같다
            }
        }
        return BookmarkStatus(articleId, true)
    }

    @Operation(summary = "즐겨찾기 해제")
    @DeleteMapping("/{articleId}")
    fun remove(@PathVariable articleId: Long, request: HttpServletRequest): BookmarkStatus {
        val member = memberAuth.requireCompleted(request)
        bookmarkRepository.deleteByMemberIdAndArticleId(member.id!!, articleId)
        return BookmarkStatus(articleId, false)
    }
}
