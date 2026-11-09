package com.atleti.news.event

import com.atleti.news.member.domain.Member
import com.atleti.news.moderation.SuspensionRepository
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * 회원 이벤트 (Redis Stream `events:member`). 회원은 이 서비스에만 있고, 다른 서비스(travel-svc)는
 * 이 이벤트로 작성자 표시용 사본(닉네임·사진·정지 여부)을 맞춘다. 계약: docs/events.md
 *
 * 회원이 바뀌는 **같은 트랜잭션 안에서** 부른다 (아웃박스, MANDATORY).
 */
@Component
class MemberEvents(
    private val outbox: OutboxWriter,
    private val suspensions: SuspensionRepository,
) {
    data class Snapshot(
        val memberId: Long,
        val nickname: String,
        val profileImageUrl: String?,
        val favoriteTeam: String?,
        val signupCompleted: Boolean,
        val role: String,              // MEMBER | TEAM_ADMIN | ADMIN
        val adminClub: String?,
        val suspendedUntil: OffsetDateTime?, // 지금 걸린 정지 (없으면 null)
        val suspensionReason: String?,
    )

    data class Deleted(val memberId: Long)

    /** 가입·로그인·프로필 수정·정지·해제·권한 변경 뒤에 부른다. 지금 상태 전체를 보낸다 (받는 쪽은 덮어쓰면 된다) */
    fun updated(member: Member) {
        val suspension = suspensions.findFirstByProviderAndProviderUserIdAndLiftedAtIsNullAndEndsAtAfterOrderByEndsAtDesc(
            member.provider, member.providerUserId, OffsetDateTime.now(),
        )
        outbox.record(
            UPDATED, member.id!!.toString(),
            Snapshot(
                memberId = member.id!!,
                nickname = member.nickname,
                profileImageUrl = member.profileImageUrl,
                favoriteTeam = member.favoriteTeam?.id,
                signupCompleted = member.signupCompleted,
                role = member.effectiveRole.name,
                adminClub = member.adminClub,
                suspendedUntil = suspension?.endsAt,
                suspensionReason = suspension?.reason,
            ),
        )
    }

    /** 탈퇴. 받는 쪽은 작성자를 "탈퇴한 회원"으로 보여 준다 (글은 남긴다) */
    fun deleted(memberId: Long) = outbox.record(DELETED, memberId.toString(), Deleted(memberId))

    companion object {
        const val UPDATED = "member.updated"
        const val DELETED = "member.deleted"
    }
}
