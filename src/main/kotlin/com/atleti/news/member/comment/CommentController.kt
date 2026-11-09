package com.atleti.news.member.comment

import com.atleti.news.chat.domain.ChatException
import com.atleti.news.chat.domain.SlangFilterService
import com.atleti.news.common.ForbiddenException
import com.atleti.news.common.InvalidRequestException
import com.atleti.news.common.NotFoundException
import com.atleti.news.member.auth.MemberAuth
import com.atleti.news.member.domain.MemberRepository
import com.atleti.news.moderation.ModerationService
import com.atleti.news.moderation.ReportService
import com.atleti.news.news.domain.NewsArticleRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Duration
import java.time.OffsetDateTime

data class CommentRequest(val content: String?)

data class CommentResponse(
    val id: Long,
    val content: String,
    val nickname: String,
    val profileImageUrl: String?,
    val createdAt: OffsetDateTime,
    val mine: Boolean, // 로그인한 본인 댓글이면 true — 프론트가 삭제 버튼을 띄운다
    val favoriteTeam: String? = null, // 작성자의 응원팀 ("arsenal" … "other"). 탈퇴했으면 null
    val moderatable: Boolean = false, // 지금 보는 사람이 이 댓글을 지우고 작성자를 정지할 수 있는 관리자인지
    val reported: Boolean = false, // 지금 보는 사람이 이미 신고한 댓글인지
    val reportCount: Int? = null, // 처리 안 된 신고 수 — 관리자(moderatable)에게만, 나머지는 null
)

data class ReportRequest(val reason: String?, val detail: String? = null)

@Tag(name = "Comment API", description = "기사 댓글")
@RestController
class CommentController(
    private val commentRepository: CommentRepository,
    private val memberRepository: MemberRepository,
    private val newsArticleRepository: NewsArticleRepository,
    private val memberAuth: MemberAuth,
    private val slangFilter: SlangFilterService,
    private val moderation: ModerationService,
    private val reports: ReportService,
) {
    @Operation(summary = "댓글 목록", description = "오래된 순. 비로그인도 볼 수 있다")
    @GetMapping("/api/news/{articleId}/comments")
    fun list(
        @PathVariable articleId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        request: HttpServletRequest,
    ): Page<CommentResponse> {
        val viewer = memberAuth.current(request)
        val me = viewer?.id
        val moderatable = moderation.canModerateArticle(viewer, articleId)
        val comments = commentRepository.findAllByArticleId(
            articleId,
            PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100), Sort.by("createdAt").ascending()),
        )
        // 작성자를 한 번에 읽는다 (댓글마다 조회하지 않게)
        val members = memberRepository.findAllById(comments.content.map { it.memberId }.toSet()).associateBy { it.id }
        val ids = comments.content.map { it.id!! }
        val reportedByMe = reports.reportedBy(me, ids)
        val openCounts = if (moderatable) reports.openCounts(ids) else emptyMap()
        return comments.map { c ->
            val author = members[c.memberId]
            CommentResponse(
                id = c.id!!,
                content = c.content,
                nickname = author?.nickname ?: "탈퇴한 회원",
                profileImageUrl = author?.profileImageUrl,
                createdAt = c.createdAt,
                mine = c.memberId == me,
                favoriteTeam = author?.favoriteTeam?.id,
                moderatable = moderatable,
                reported = c.id in reportedByMe,
                reportCount = if (moderatable) openCounts[c.id] ?: 0 else null,
            )
        }
    }

    @Operation(summary = "댓글 쓰기 (로그인 필요)")
    @PostMapping("/api/news/{articleId}/comments")
    fun create(@PathVariable articleId: Long, @RequestBody body: CommentRequest, request: HttpServletRequest): ResponseEntity<CommentResponse> {
        val member = memberAuth.requireCompleted(request)
        moderation.requireNotSuspended(member)
        if (!newsArticleRepository.existsById(articleId)) throw NotFoundException("해당 기사를 찾을 수 없습니다. ID: $articleId")

        val content = validate(body.content)
        // 연속 도배 방지: 한 사람당 COOLDOWN에 하나
        commentRepository.findFirstByMemberIdOrderByCreatedAtDesc(member.id!!)?.let { last ->
            if (Duration.between(last.createdAt, OffsetDateTime.now()) < COOLDOWN) {
                throw InvalidRequestException("댓글은 ${COOLDOWN.seconds}초에 한 번 쓸 수 있습니다.")
            }
        }

        val saved = commentRepository.save(Comment(articleId = articleId, memberId = member.id!!, content = content))
        return ResponseEntity.status(HttpStatus.CREATED).body(
            CommentResponse(saved.id!!, saved.content, member.nickname, member.profileImageUrl, saved.createdAt, mine = true, favoriteTeam = member.favoriteTeam?.id, moderatable = moderation.canModerateArticle(member, articleId))
        )
    }

    @Operation(summary = "댓글 삭제 (본인 것만)")
    @DeleteMapping("/api/comments/{commentId}")
    fun delete(@PathVariable commentId: Long, request: HttpServletRequest): ResponseEntity<Void> {
        val member = memberAuth.requireCompleted(request)
        val comment = commentRepository.findById(commentId).orElseThrow { NotFoundException("댓글을 찾을 수 없습니다.") }
        if (comment.memberId != member.id) throw ForbiddenException("본인이 쓴 댓글만 지울 수 있습니다.")
        commentRepository.delete(comment)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "댓글 신고 (로그인 필요)", description = "reason: ABUSE | SPAM | FLOOD | OTHER (OTHER면 detail 필수). 같은 댓글은 한 번만 (409)")
    @PostMapping("/api/comments/{commentId}/report")
    fun report(@PathVariable commentId: Long, @RequestBody body: ReportRequest, request: HttpServletRequest): ResponseEntity<Void> {
        val member = memberAuth.requireCompleted(request)
        reports.report(member, commentId, body.reason, body.detail)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    private fun validate(raw: String?): String {
        val content = raw?.trim().orEmpty()
        if (content.isEmpty()) throw InvalidRequestException("댓글 내용을 입력해 주세요.")
        if (content.length > MAX_LENGTH) throw InvalidRequestException("댓글은 ${MAX_LENGTH}자까지 쓸 수 있습니다.")
        // 채팅과 같은 금칙어·연락처 필터
        try {
            slangFilter.validateText(content, "댓글")
        } catch (e: ChatException) {
            throw InvalidRequestException(e.message ?: "쓸 수 없는 내용입니다.")
        }
        return content
    }

    companion object {
        const val MAX_LENGTH = 500
        val COOLDOWN: Duration = Duration.ofSeconds(10)
    }
}
