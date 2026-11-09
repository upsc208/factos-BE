package com.atleti.news.moderation

import com.atleti.news.common.ConflictException
import com.atleti.news.common.InvalidRequestException
import com.atleti.news.common.NotFoundException
import com.atleti.news.member.comment.CommentRepository
import com.atleti.news.member.domain.Member
import com.atleti.news.member.domain.MemberRepository
import com.atleti.news.member.domain.MemberRole
import com.atleti.news.news.domain.NewsArticleRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/** 관리자 신고 목록 한 칸: 댓글 하나에 모인 신고들 */
data class ReportedComment(
    val commentId: Long,
    val commentContent: String,
    val authorNickname: String,
    val authorMemberId: Long?,
    val articleId: Long,
    val articleTitle: String,
    val articleClub: String?, // 기사 링크용 (/{club}/news/{id})
    val reportCount: Int,
    val reasons: Map<ReportReason, Int>,
    val details: List<String>, // 신고자가 덧붙인 말 (최근 것부터, 최대 5개)
    val firstReportedAt: OffsetDateTime,
    val lastReportedAt: OffsetDateTime,
)

/**
 * 댓글 신고. 회원이 신고하면 관리자 화면에 댓글별로 모인다.
 * 관리자가 댓글을 지우거나 작성자를 정지하면 열린 신고가 RESOLVED로 닫히고 ([ModerationService]),
 * 문제없다고 보면 [dismiss]로 DISMISSED.
 */
@Service
class ReportService(
    private val reports: CommentReportRepository,
    private val comments: CommentRepository,
    private val members: MemberRepository,
    private val articles: NewsArticleRepository,
    private val moderation: ModerationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun report(reporter: Member, commentId: Long, rawReason: String?, rawDetail: String?) {
        val reason = ReportReason.entries.find { it.name == rawReason?.trim()?.uppercase() }
            ?: throw InvalidRequestException("신고 사유를 골라 주세요.")
        val detail = rawDetail?.trim()?.takeIf { it.isNotEmpty() }
        if (detail != null && detail.length > MAX_DETAIL) throw InvalidRequestException("신고 내용은 ${MAX_DETAIL}자까지 쓸 수 있습니다.")
        if (reason == ReportReason.OTHER && detail == null) throw InvalidRequestException("기타 사유를 적어 주세요.")

        val comment = comments.findById(commentId).orElseThrow { NotFoundException("댓글을 찾을 수 없습니다.") }
        if (comment.memberId == reporter.id) throw InvalidRequestException("내 댓글은 신고할 수 없습니다.")
        if (reports.existsByCommentIdAndReporterId(commentId, reporter.id!!)) throw ConflictException("이미 신고한 댓글입니다.")
        // 신고 남발 방지
        if (reports.countByReporterIdAndCreatedAtAfter(reporter.id!!, OffsetDateTime.now().minusDays(1)) >= DAILY_LIMIT) {
            throw InvalidRequestException("신고는 하루 ${DAILY_LIMIT}건까지 할 수 있습니다.")
        }

        try {
            reports.save(CommentReport(commentId = commentId, articleId = comment.articleId, reporterId = reporter.id!!, reason = reason, detail = detail))
        } catch (e: DataIntegrityViolationException) {
            throw ConflictException("이미 신고한 댓글입니다.") // 같은 사람이 동시에 두 번 누른 경우
        }
        log.info("🚩 [Report] comment={} by={} reason={}", commentId, reporter.id, reason)
    }

    /** 내가 신고한 댓글 id (댓글 목록에서 "신고됨" 표시용) */
    fun reportedBy(reporterId: Long?, commentIds: Collection<Long>): Set<Long> =
        if (reporterId == null || commentIds.isEmpty()) emptySet()
        else reports.findAllByReporterIdAndCommentIdIn(reporterId, commentIds).map { it.commentId }.toSet()

    /** 댓글별 열린 신고 수 (관리자에게만 보여 준다) */
    fun openCounts(commentIds: Collection<Long>): Map<Long, Int> =
        if (commentIds.isEmpty()) emptyMap()
        else reports.findAllByStatusAndCommentIdIn(ReportStatus.OPEN, commentIds).groupingBy { it.commentId }.eachCount()

    /**
     * 관리자 신고 목록. 열린 신고를 댓글별로 모아 신고 많은 순 → 최근 순.
     * 팀 관리자는 자기 팀 기사 댓글만. 이미 지워진 댓글의 신고는 여기서 닫는다.
     */
    @Transactional
    fun openReports(admin: Member): List<ReportedComment> {
        moderation.requireModerator(admin)
        val open = reports.findTop500ByStatusOrderByCreatedAtDesc(ReportStatus.OPEN)
        if (open.isEmpty()) return emptyList()

        val byComment = open.groupBy { it.commentId }
        val commentMap = comments.findAllById(byComment.keys).associateBy { it.id!! }
        val now = OffsetDateTime.now()
        (byComment.keys - commentMap.keys).forEach { reports.closeOpen(it, ReportStatus.RESOLVED, null, now) }

        val articleMap = articles.findAllById(commentMap.values.map { it.articleId }.toSet()).associateBy { it.id!! }
        val adminClub = admin.adminClub
        val visible = commentMap.values.filter { c ->
            val article = articleMap[c.articleId] ?: return@filter admin.effectiveRole == MemberRole.ADMIN
            admin.effectiveRole == MemberRole.ADMIN || article.clubs.any { it.id == adminClub }
        }
        val authors = members.findAllById(visible.map { it.memberId }.toSet()).associateBy { it.id }

        return visible.map { c ->
            val rs = byComment.getValue(c.id!!)
            val article = articleMap[c.articleId]
            val club = if (admin.effectiveRole == MemberRole.TEAM_ADMIN) adminClub
            else article?.clubs?.minByOrNull { it.ordinal }?.id
            ReportedComment(
                commentId = c.id!!,
                commentContent = c.content,
                authorNickname = authors[c.memberId]?.nickname ?: "탈퇴한 회원",
                authorMemberId = authors[c.memberId]?.id,
                articleId = c.articleId,
                articleTitle = article?.titleKo ?: article?.titleEn ?: "삭제된 기사",
                articleClub = club,
                reportCount = rs.size,
                reasons = rs.groupingBy { it.reason }.eachCount(),
                details = rs.mapNotNull { it.detail }.take(5),
                firstReportedAt = rs.minOf { it.createdAt },
                lastReportedAt = rs.maxOf { it.createdAt },
            )
        }.sortedWith(compareByDescending<ReportedComment> { it.reportCount }.thenByDescending { it.lastReportedAt })
    }

    /** 문제없음: 그 댓글의 열린 신고를 닫는다 (댓글은 그대로) */
    @Transactional
    fun dismiss(admin: Member, commentId: Long): Int {
        moderation.requireModerator(admin)
        val comment = comments.findById(commentId).orElseThrow { NotFoundException("댓글을 찾을 수 없습니다.") }
        if (!moderation.canModerateArticle(admin, comment.articleId)) {
            throw com.atleti.news.common.ForbiddenException("담당 팀 기사의 댓글만 관리할 수 있습니다.")
        }
        val closed = reports.closeOpen(commentId, ReportStatus.DISMISSED, admin.nickname, OffsetDateTime.now())
        log.info("👌 [Report] 신고 기각 comment={} reports={} by={}", commentId, closed, admin.id)
        return closed
    }

    companion object {
        const val MAX_DETAIL = 200
        const val DAILY_LIMIT = 30
    }
}
