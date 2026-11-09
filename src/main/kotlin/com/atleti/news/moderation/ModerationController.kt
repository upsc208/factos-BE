package com.atleti.news.moderation

import com.atleti.news.common.InvalidRequestException
import com.atleti.news.common.NotFoundException
import com.atleti.news.member.auth.MemberAuth
import com.atleti.news.member.domain.MemberRepository
import com.atleti.news.member.domain.MemberRole
import com.atleti.news.news.domain.Club
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.OffsetDateTime

data class SuspendRequest(
    val reason: String?,
    val days: Int = 1,
    val deleteComment: Boolean = true,
)

data class SuspensionResponse(
    val id: Long,
    val memberId: Long?,
    val nickname: String,
    val reason: String,
    val club: String?,
    val commentContent: String?,
    val articleId: Long?,
    val createdByNickname: String,
    val createdAt: OffsetDateTime,
    val endsAt: OffsetDateTime,
    val liftedAt: OffsetDateTime?,
    val liftedByNickname: String?,
    val active: Boolean,
    val liftable: Boolean, // 지금 요청한 관리자가 해제할 수 있는지 — 프론트가 버튼을 띄운다
) {
    companion object {
        fun of(s: Suspension, viewer: com.atleti.news.member.domain.Member) = SuspensionResponse(
            id = s.id!!, memberId = s.memberId, nickname = s.nickname, reason = s.reason, club = s.club,
            commentContent = s.commentContent, articleId = s.articleId,
            createdByNickname = s.createdByNickname, createdAt = s.createdAt, endsAt = s.endsAt,
            liftedAt = s.liftedAt, liftedByNickname = s.liftedByNickname, active = s.isActive(),
            liftable = s.isActive() && (viewer.effectiveRole == MemberRole.ADMIN || s.club == viewer.adminClub),
        )
    }
}

/**
 * 회원 관리자(팀 관리자·전체 관리자)용 API. 로그인 쿠키로 동작하고, 권한은 [ModerationService]가 본다.
 * 운영자용 `/api/admin 아래`(관리자 토큰)과는 별개다.
 */
@Tag(name = "Moderation API", description = "관리자: 신고 목록, 댓글 삭제, 임시 정지, 정지 목록")
@RestController
@RequestMapping("/api/moderation")
class ModerationController(
    private val moderation: ModerationService,
    private val suspensions: SuspensionRepository,
    private val memberAuth: MemberAuth,
    private val reports: ReportService,
) {
    @Operation(summary = "신고된 댓글 목록 (관리자)", description = "처리 안 된 신고를 댓글별로 모아 신고 많은 순. 팀 관리자는 자기 팀 기사 댓글만")
    @GetMapping("/reports")
    fun reports(request: HttpServletRequest): List<ReportedComment> = reports.openReports(memberAuth.require(request))

    @Operation(summary = "신고 기각 (관리자)", description = "문제없는 댓글: 열린 신고만 닫고 댓글은 남긴다")
    @PostMapping("/reports/{commentId}/dismiss")
    fun dismiss(@PathVariable commentId: Long, request: HttpServletRequest): Map<String, Int> =
        mapOf("closed" to reports.dismiss(memberAuth.require(request), commentId))

    @Operation(summary = "댓글 삭제 (관리자)", description = "팀 관리자는 자기 팀 기사의 댓글만")
    @DeleteMapping("/comments/{commentId}")
    fun deleteComment(@PathVariable commentId: Long, request: HttpServletRequest): ResponseEntity<Void> {
        moderation.deleteComment(memberAuth.require(request), commentId)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "댓글 작성자 임시 정지 (관리자)", description = "사유 필수, 기간 1·3·7·30일. deleteComment면 댓글도 지운다")
    @PostMapping("/comments/{commentId}/suspend")
    fun suspend(@PathVariable commentId: Long, @RequestBody body: SuspendRequest, request: HttpServletRequest): ResponseEntity<SuspensionResponse> {
        val admin = memberAuth.require(request)
        val saved = moderation.suspendCommentAuthor(admin, commentId, body.reason, body.days, body.deleteComment)
        return ResponseEntity.status(HttpStatus.CREATED).body(SuspensionResponse.of(saved, admin))
    }

    @Operation(summary = "정지 목록 (관리자)", description = "최근 순. active=true면 지금 정지 중인 것만, club으로 관리 범위를 거른다")
    @GetMapping("/suspensions")
    fun list(
        @RequestParam(defaultValue = "true") active: Boolean,
        @RequestParam(required = false) club: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        request: HttpServletRequest,
    ): Page<SuspensionResponse> {
        val admin = memberAuth.require(request)
        moderation.requireModerator(admin)
        val clubFilter = club?.takeIf { it.isNotBlank() }?.let { Club.fromId(it)?.id ?: throw NotFoundException("알 수 없는 팀입니다: $it") }
        return suspensions.search(
            active, OffsetDateTime.now(), clubFilter,
            PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100), Sort.by("createdAt").descending()),
        ).map { SuspensionResponse.of(it, admin) }
    }

    @Operation(summary = "정지 해제 (관리자)", description = "팀 관리자는 자기 팀 범위에서 내린 정지만")
    @PostMapping("/suspensions/{id}/lift")
    fun lift(@PathVariable id: Long, request: HttpServletRequest): SuspensionResponse {
        val admin = memberAuth.require(request)
        return SuspensionResponse.of(moderation.lift(admin, id), admin)
    }
}

data class MemberRoleRequest(val role: String?, val club: String?)

data class AdminMemberResponse(
    val id: Long,
    val nickname: String,
    val provider: String,
    val role: MemberRole,
    val adminClub: String?,
    val createdAt: OffsetDateTime,
)

/**
 * 운영자 전용 (`/api/admin 아래` — 관리자 토큰이나 X-Admin-Key). 회원에게 관리자 권한을 주고 뺀다.
 * 회원 관리자는 서로의 권한을 바꿀 수 없다.
 */
@Tag(name = "Admin Member API", description = "운영자: 회원 검색, 관리자 권한 부여")
@RestController
@RequestMapping("/api/admin/members")
class AdminMemberController(
    private val members: MemberRepository,
    private val memberEvents: com.atleti.news.event.MemberEvents,
) {

    @Operation(summary = "닉네임으로 회원 찾기")
    @GetMapping
    fun search(@RequestParam nickname: String): List<AdminMemberResponse> {
        val key = nickname.trim().lowercase()
        if (key.isEmpty()) throw InvalidRequestException("닉네임을 입력해 주세요.")
        return members.findAllByNicknameKeyContaining(key).take(50).map(::toResponse)
    }

    @Operation(summary = "권한 바꾸기", description = "role: MEMBER | TEAM_ADMIN | ADMIN. TEAM_ADMIN이면 club(arsenal…) 필수")
    @PutMapping("/{id}/role")
    @org.springframework.transaction.annotation.Transactional
    fun changeRole(@PathVariable id: Long, @RequestBody body: MemberRoleRequest): AdminMemberResponse {
        val member = members.findById(id).orElseThrow { NotFoundException("회원을 찾을 수 없습니다. ID: $id") }
        val role = MemberRole.entries.find { it.name == body.role?.trim()?.uppercase() }
            ?: throw InvalidRequestException("role은 MEMBER, TEAM_ADMIN, ADMIN 중 하나입니다.")
        val club = if (role == MemberRole.TEAM_ADMIN) {
            Club.fromId(body.club ?: "")?.id ?: throw InvalidRequestException("팀 관리자는 club(arsenal, chelsea, liverpool, mancity, manutd, tottenham)이 필요합니다.")
        } else null
        member.role = role.takeIf { it != MemberRole.MEMBER }
        member.adminClub = club
        return toResponse(members.save(member).also { memberEvents.updated(it) })
    }

    private fun toResponse(m: com.atleti.news.member.domain.Member) =
        AdminMemberResponse(m.id!!, m.nickname, m.provider.name, m.effectiveRole, m.adminClub, m.createdAt)
}
