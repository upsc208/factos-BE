package com.atleti.news.moderation

import com.atleti.news.common.ForbiddenException
import com.atleti.news.common.InvalidRequestException
import com.atleti.news.common.NotFoundException
import com.atleti.news.member.comment.Comment
import com.atleti.news.member.comment.CommentRepository
import com.atleti.news.member.domain.Member
import com.atleti.news.member.domain.MemberRepository
import com.atleti.news.member.domain.MemberRole
import com.atleti.news.news.domain.NewsArticleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 관리자 기능: 댓글 삭제, 댓글 작성자 임시 정지(사유 필수), 정지 해제.
 * 댓글을 지우거나 작성자를 정지하면 그 댓글의 열린 신고([CommentReport])가 닫힌다.
 *
 * 범위: 전체 관리자(ADMIN)는 모든 기사, 팀 관리자(TEAM_ADMIN)는 **자기 팀이 걸린 기사**의 댓글만.
 * 여러 팀에 걸린 기사(같은 기사가 아스날·첼시 피드에 모두 나온 경우)는 두 팀 관리자 모두 다룰 수 있다.
 */
@Service
class ModerationService(
    private val suspensionRepository: SuspensionRepository,
    private val commentRepository: CommentRepository,
    private val memberRepository: MemberRepository,
    private val newsArticleRepository: NewsArticleRepository,
    private val reportRepository: CommentReportRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 이 회원에게 지금 걸린 정지. 없으면 null */
    fun activeSuspension(member: Member): Suspension? =
        suspensionRepository.findFirstByProviderAndProviderUserIdAndLiftedAtIsNullAndEndsAtAfterOrderByEndsAtDesc(
            member.provider, member.providerUserId, OffsetDateTime.now(),
        )

    /** 댓글 쓰기 전에 부른다. 정지 중이면 403 + 언제까지·왜 */
    fun requireNotSuspended(member: Member) {
        val s = activeSuspension(member) ?: return
        throw ForbiddenException("${s.endsAt.atZoneSameInstant(KST).format(UNTIL_FORMAT)}까지 댓글 작성이 정지된 상태입니다. 사유: ${s.reason}")
    }

    /** 이 관리자가 이 기사의 댓글을 다룰 수 있는지 */
    fun canModerateArticle(admin: Member?, articleId: Long): Boolean = when (admin?.effectiveRole) {
        MemberRole.ADMIN -> true
        MemberRole.TEAM_ADMIN -> {
            val club = admin!!.adminClub
            club != null && newsArticleRepository.findById(articleId).map { a -> a.clubs.any { it.id == club } }.orElse(false)
        }
        else -> false
    }

    @Transactional
    fun deleteComment(admin: Member, commentId: Long) {
        val comment = moderatableComment(admin, commentId)
        commentRepository.delete(comment)
        reportRepository.closeOpen(commentId, ReportStatus.RESOLVED, admin.nickname, OffsetDateTime.now())
        log.info("🧹 [Moderation] 댓글 삭제 comment={} by={}", commentId, admin.id)
    }

    /**
     * 댓글 작성자를 [days]일 정지한다. [deleteComment]면 그 댓글도 지운다 (내용은 정지 기록에 사본으로 남는다).
     */
    @Transactional
    fun suspendCommentAuthor(admin: Member, commentId: Long, rawReason: String?, days: Int, deleteComment: Boolean): Suspension {
        val reason = rawReason?.trim().orEmpty()
        if (reason.isEmpty()) throw InvalidRequestException("정지 사유를 입력해 주세요.")
        if (reason.length > MAX_REASON) throw InvalidRequestException("정지 사유는 ${MAX_REASON}자까지 쓸 수 있습니다.")
        if (days !in ALLOWED_DAYS) throw InvalidRequestException("정지 기간은 ${ALLOWED_DAYS.joinToString("·")}일 중에서 고를 수 있습니다.")

        val comment = moderatableComment(admin, commentId)
        val target = memberRepository.findById(comment.memberId)
            .orElseThrow { NotFoundException("탈퇴한 회원이라 정지할 수 없습니다.") }
        if (target.id == admin.id) throw InvalidRequestException("자기 자신은 정지할 수 없습니다.")
        if (target.isModerator) throw ForbiddenException("관리자는 정지할 수 없습니다. 운영자에게 권한 해제를 요청해 주세요.")

        val saved = suspensionRepository.save(
            Suspension(
                memberId = target.id,
                provider = target.provider,
                providerUserId = target.providerUserId,
                nickname = target.nickname,
                reason = reason,
                club = scopeClub(admin, comment.articleId),
                commentId = comment.id,
                commentContent = comment.content,
                articleId = comment.articleId,
                createdById = admin.id!!,
                createdByNickname = admin.nickname,
                endsAt = OffsetDateTime.now().plusDays(days.toLong()),
            )
        )
        if (deleteComment) commentRepository.delete(comment)
        // 신고된 댓글이면 정지로 처리된 것 (댓글을 남겨 둬도)
        reportRepository.closeOpen(commentId, ReportStatus.RESOLVED, admin.nickname, OffsetDateTime.now())
        log.info("⛔ [Moderation] 정지 member={} days={} by={} comment={}", target.id, days, admin.id, commentId)
        return saved
    }

    @Transactional
    fun lift(admin: Member, suspensionId: Long): Suspension {
        requireModerator(admin)
        val s = suspensionRepository.findById(suspensionId).orElseThrow { NotFoundException("정지 기록을 찾을 수 없습니다.") }
        if (admin.effectiveRole == MemberRole.TEAM_ADMIN && s.club != admin.adminClub) {
            throw ForbiddenException("다른 팀 관리 범위에서 내린 정지는 해제할 수 없습니다.")
        }
        if (!s.isActive()) throw InvalidRequestException("이미 끝났거나 해제된 정지입니다.")
        s.liftedAt = OffsetDateTime.now()
        s.liftedByNickname = admin.nickname
        log.info("✅ [Moderation] 정지 해제 suspension={} by={}", suspensionId, admin.id)
        return s
    }

    fun requireModerator(member: Member) {
        if (!member.isModerator) throw ForbiddenException("관리자만 할 수 있습니다.")
    }

    private fun moderatableComment(admin: Member, commentId: Long): Comment {
        requireModerator(admin)
        val comment = commentRepository.findById(commentId).orElseThrow { NotFoundException("댓글을 찾을 수 없습니다.") }
        if (!canModerateArticle(admin, comment.articleId)) throw ForbiddenException("담당 팀 기사의 댓글만 관리할 수 있습니다.")
        return comment
    }

    /** 정지 기록에 남길 관리 범위: 팀 관리자는 자기 팀, 전체 관리자는 기사의 첫 팀 */
    private fun scopeClub(admin: Member, articleId: Long): String? =
        if (admin.effectiveRole == MemberRole.TEAM_ADMIN) admin.adminClub
        else newsArticleRepository.findById(articleId).map { a -> a.clubs.minByOrNull { it.ordinal }?.id }.orElse(null)

    companion object {
        const val MAX_REASON = 500
        val ALLOWED_DAYS = listOf(1, 3, 7, 30)
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val UNTIL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
