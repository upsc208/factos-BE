package com.atleti.news.moderation

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

/** 신고 사유. 프론트 버튼과 같은 목록 */
enum class ReportReason(val label: String) {
    ABUSE("욕설·비하"),
    SPAM("광고·스팸"),
    FLOOD("도배"),
    OTHER("기타"),
}

enum class ReportStatus {
    OPEN,      // 관리자가 아직 안 봄
    RESOLVED,  // 관리자가 댓글을 지우거나 작성자를 정지함 (또는 작성자가 스스로 지움)
    DISMISSED, // 관리자가 문제없다고 봄
}

/**
 * 회원이 남긴 댓글 신고. 한 사람이 같은 댓글을 두 번 신고할 수 없다.
 * 관리자 화면에는 댓글별로 모아 보여 준다 ([ReportService.openReports]).
 * 댓글이 지워져도 신고 기록은 남는다 (어떤 신고를 어떻게 처리했는지).
 */
@Entity
@Table(
    name = "comment_report",
    uniqueConstraints = [UniqueConstraint(name = "uk_report_comment_reporter", columnNames = ["comment_id", "reporter_id"])],
    indexes = [Index(name = "idx_report_status", columnList = "status, created_at")],
)
class CommentReport(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "comment_id", nullable = false)
    val commentId: Long,

    @Column(name = "article_id", nullable = false)
    val articleId: Long,

    @Column(name = "reporter_id", nullable = false)
    val reporterId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val reason: ReportReason,

    // 기타 사유나 덧붙이는 말 (선택)
    @Column(length = 200)
    val detail: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ReportStatus = ReportStatus.OPEN,

    @Column(name = "handled_by_nickname", length = 40)
    var handledByNickname: String? = null,

    @Column(name = "handled_at")
    var handledAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface CommentReportRepository : JpaRepository<CommentReport, Long> {
    fun existsByCommentIdAndReporterId(commentId: Long, reporterId: Long): Boolean

    fun findAllByReporterIdAndCommentIdIn(reporterId: Long, commentIds: Collection<Long>): List<CommentReport>

    fun findAllByStatusAndCommentIdIn(status: ReportStatus, commentIds: Collection<Long>): List<CommentReport>

    fun findTop500ByStatusOrderByCreatedAtDesc(status: ReportStatus): List<CommentReport>

    fun countByReporterIdAndCreatedAtAfter(reporterId: Long, since: OffsetDateTime): Long

    /** 그 댓글의 열린 신고를 한꺼번에 닫는다 */
    @Modifying
    @Query(
        """
        UPDATE CommentReport r SET r.status = :status, r.handledByNickname = :by, r.handledAt = :at
        WHERE r.commentId = :commentId AND r.status = com.atleti.news.moderation.ReportStatus.OPEN
        """
    )
    fun closeOpen(
        @Param("commentId") commentId: Long,
        @Param("status") status: ReportStatus,
        @Param("by") by: String?,
        @Param("at") at: OffsetDateTime,
    ): Int
}
