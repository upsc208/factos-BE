package com.atleti.news.member.domain

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

enum class AuthProvider { KAKAO, GOOGLE }

/**
 * 회원 권한. 관리자는 따로 계정을 두지 않고 회원에게 권한을 붙인다.
 * - [TEAM_ADMIN]: 자기 팀([Member.adminClub]) 기사에 달린 댓글만 지우고, 그 작성자를 정지할 수 있다
 * - [ADMIN]: 모든 팀
 * 권한은 운영자 API(`/api/admin/members`, 관리자 토큰·키)로만 바꾼다
 */
enum class MemberRole { MEMBER, TEAM_ADMIN, ADMIN }

/** 응원하는 팀: Big6 + 기타. [id]는 프론트 라우트·API 값 ("arsenal" … "other") */
enum class FavoriteTeam(val id: String) {
    ARSENAL("arsenal"), CHELSEA("chelsea"), LIVERPOOL("liverpool"),
    MAN_CITY("mancity"), MAN_UTD("manutd"), TOTTENHAM("tottenham"),
    OTHER("other");

    companion object {
        fun fromId(id: String?): FavoriteTeam? = entries.find { it.id == id?.trim()?.lowercase() }
    }
}

/**
 * 회원 (댓글·즐겨찾기용). 카카오·구글 로그인으로만 가입한다 — 비밀번호를 저장하지 않는다.
 *
 * 가입은 두 단계다: 첫 로그인 때 임시 닉네임으로 행을 만들고([signupCompleted]=false),
 * 프론트의 "가입 마치기"에서 닉네임·응원팀을 정하면 true가 된다. 그 전에는 댓글을 못 쓴다.
 *
 * 이메일은 받지 않는다. 테이블 이름이 `user`가 아닌 이유: PostgreSQL 예약어다.
 */
@Entity
@Table(
    name = "member",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_member_provider_user", columnNames = ["provider", "provider_user_id"]),
        // 대소문자만 다른 닉네임("Gunner" / "gunner")도 중복으로 본다 → 소문자 키에 유니크
        UniqueConstraint(name = "uk_member_nickname_key", columnNames = ["nickname_key"]),
    ],
)
class Member(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val provider: AuthProvider,

    // 카카오 회원번호 / 구글 sub. 제공자 안에서만 고유하다
    @Column(name = "provider_user_id", nullable = false)
    val providerUserId: String,

    nickname: String,

    @Column(name = "profile_image_url", columnDefinition = "TEXT")
    var profileImageUrl: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "favorite_team")
    var favoriteTeam: FavoriteTeam? = null,

    @Column(name = "signup_completed", nullable = false)
    var signupCompleted: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_login_at", nullable = false)
    var lastLoginAt: OffsetDateTime = OffsetDateTime.now(),

    // 운영 DB는 ddl-auto=update라 기존 행이 있다 → nullable. null이면 일반 회원
    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20)
    var role: MemberRole? = null,

    // TEAM_ADMIN이 맡은 팀 (Club.id: "arsenal" …)
    @Column(name = "admin_club", length = 20)
    var adminClub: String? = null,
) {
    val effectiveRole: MemberRole get() = role ?: MemberRole.MEMBER
    val isModerator: Boolean get() = effectiveRole != MemberRole.MEMBER

    @Column(nullable = false, length = 40)
    var nickname: String = nickname
        set(value) {
            field = value
            nicknameKey = keyOf(value)
        }

    @Column(name = "nickname_key", nullable = false, length = 40)
    var nicknameKey: String = keyOf(nickname)
        protected set

    companion object {
        fun keyOf(nickname: String) = nickname.trim().lowercase()
    }
}

interface MemberRepository : JpaRepository<Member, Long> {
    fun findByProviderAndProviderUserId(provider: AuthProvider, providerUserId: String): Member?
    fun existsByNicknameKey(nicknameKey: String): Boolean
    fun findByNicknameKey(nicknameKey: String): Member?
    fun findAllByNicknameKeyContaining(part: String): List<Member>
    fun findAllByRoleIn(roles: Collection<MemberRole>): List<Member>
}
