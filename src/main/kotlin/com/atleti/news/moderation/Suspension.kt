package com.atleti.news.moderation

import com.atleti.news.member.domain.AuthProvider
import jakarta.persistence.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

/**
 * 회원 임시 정지 (관리자 신고). 정지 중에는 댓글을 못 쓴다.
 *
 * 정지 대상은 회원 id가 아니라 **로그인 계정(provider + providerUserId)**으로도 기록한다.
 * 정지 중에 탈퇴하고 같은 카카오·구글 계정으로 다시 가입해도 정지가 그대로 걸리게 하려는 것.
 * 신고한 댓글 내용은 지워질 수 있어 사본을 남긴다.
 */
@Entity
@Table(
    name = "member_suspension",
    indexes = [
        Index(name = "idx_suspension_account", columnList = "provider, provider_user_id"),
        Index(name = "idx_suspension_ends", columnList = "ends_at"),
    ],
)
class Suspension(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // 탈퇴하면 회원 행이 사라지므로 null이 될 수 있다 (계정 키로 계속 찾는다)
    @Column(name = "member_id")
    var memberId: Long?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val provider: AuthProvider,

    @Column(name = "provider_user_id", nullable = false)
    val providerUserId: String,

    // 정지 당시 닉네임 (목록 표시용 사본)
    @Column(nullable = false, length = 40)
    val nickname: String,

    @Column(nullable = false, length = 500)
    val reason: String,

    // 어느 팀 관리 범위에서 내린 정지인지 (Club.id). 팀 관리자는 자기 팀 것만 해제할 수 있다
    @Column(length = 20)
    val club: String?,

    @Column(name = "comment_id")
    val commentId: Long?,

    @Column(name = "comment_content", columnDefinition = "TEXT")
    val commentContent: String?,

    @Column(name = "article_id")
    val articleId: Long?,

    @Column(name = "created_by_id", nullable = false)
    val createdById: Long,

    @Column(name = "created_by_nickname", nullable = false, length = 40)
    val createdByNickname: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "ends_at", nullable = false)
    val endsAt: OffsetDateTime,

    @Column(name = "lifted_at")
    var liftedAt: OffsetDateTime? = null,

    @Column(name = "lifted_by_nickname", length = 40)
    var liftedByNickname: String? = null,
) {
    fun isActive(now: OffsetDateTime = OffsetDateTime.now()) = liftedAt == null && endsAt.isAfter(now)
}

interface SuspensionRepository : JpaRepository<Suspension, Long> {

    /** 이 계정에 지금 걸린 정지 중 가장 늦게 끝나는 것 */
    fun findFirstByProviderAndProviderUserIdAndLiftedAtIsNullAndEndsAtAfterOrderByEndsAtDesc(
        provider: AuthProvider, providerUserId: String, now: OffsetDateTime,
    ): Suspension?

    @Query(
        """
        SELECT s FROM Suspension s
        WHERE (:activeOnly = false OR (s.liftedAt IS NULL AND s.endsAt > :now))
          AND (:club IS NULL OR s.club = :club)
        """
    )
    fun search(
        @Param("activeOnly") activeOnly: Boolean,
        @Param("now") now: OffsetDateTime,
        @Param("club") club: String?,
        pageable: Pageable,
    ): Page<Suspension>

    fun findAllByMemberId(memberId: Long): List<Suspension>
}
