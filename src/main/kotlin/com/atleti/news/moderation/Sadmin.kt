package com.atleti.news.moderation

import com.atleti.news.common.ForbiddenException
import com.atleti.news.common.InvalidRequestException
import com.atleti.news.common.NotFoundException
import com.atleti.news.event.MemberEvents
import com.atleti.news.member.auth.MemberAuth
import com.atleti.news.member.domain.Member
import com.atleti.news.member.domain.MemberRepository
import com.atleti.news.member.domain.MemberRole
import com.atleti.news.news.domain.Club
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

/*
 * 권한 세 단계 (화면 이름 = 저장 값)
 * - sadmin = ADMIN: 개발자·운영자. 전부 + 통계(Grafana) + 팀 관리자 지정. 운영자 API(X-Admin-Key)로만 만든다
 * - admin  = TEAM_ADMIN: 팀별 관리자. 자기 팀 범위의 신고 처리·댓글 삭제·정지
 * - user   = MEMBER(null): 일반 회원
 * 저장 값 이름은 그대로 둔다 — DB·이벤트(member.updated)·travel-svc가 같은 문자열을 쓴다
 */
val Member.isSadmin: Boolean get() = effectiveRole == MemberRole.ADMIN

@Service
class MemberRoleService(
    private val members: MemberRepository,
    private val memberEvents: MemberEvents,
) {
    fun search(nickname: String): List<AdminMemberResponse> {
        val key = nickname.trim().lowercase()
        if (key.isEmpty()) throw InvalidRequestException("닉네임을 입력해 주세요.")
        return members.findAllByNicknameKeyContaining(key).take(50).map(::toResponse)
    }

    /** 관리자 목록 (팀 관리자·sadmin) */
    fun moderators(): List<AdminMemberResponse> =
        members.findAllByRoleIn(listOf(MemberRole.TEAM_ADMIN, MemberRole.ADMIN)).sortedWith(compareBy({ it.role }, { it.adminClub }, { it.nickname })).map(::toResponse)

    /** [allowed] 안의 권한으로만 바꾼다 */
    @Transactional
    fun change(id: Long, body: MemberRoleRequest, allowed: Set<MemberRole>, guard: (Member) -> Unit = {}): AdminMemberResponse {
        val member = members.findById(id).orElseThrow { NotFoundException("회원을 찾을 수 없습니다. ID: $id") }
        guard(member)
        val role = MemberRole.entries.find { it.name == body.role?.trim()?.uppercase() }?.takeIf { it in allowed }
            ?: throw InvalidRequestException("role은 ${allowed.joinToString(", ") { it.name }} 중 하나입니다.")
        val club = if (role == MemberRole.TEAM_ADMIN) {
            Club.fromId(body.club ?: "")?.id ?: throw InvalidRequestException("팀 관리자는 club(arsenal, chelsea, liverpool, mancity, manutd, tottenham)이 필요합니다.")
        } else null
        member.role = role.takeIf { it != MemberRole.MEMBER }
        member.adminClub = club
        return toResponse(members.save(member).also { memberEvents.updated(it) })
    }

    fun toResponse(m: Member) = AdminMemberResponse(m.id!!, m.nickname, m.provider.name, m.effectiveRole, m.adminClub, m.createdAt)
}

/**
 * sadmin 전용 (로그인 쿠키). 팀 관리자(admin)를 지정·해제하고, 통계(Grafana) 출입을 nginx에 알려 준다.
 * sadmin을 만들거나 다른 sadmin을 바꾸는 건 여기서 못 한다 — 운영자 API로만
 */
@Tag(name = "Sadmin API", description = "sadmin: 팀 관리자 지정, 통계 출입 확인")
@RestController
class SadminController(
    private val memberAuth: MemberAuth,
    private val roles: MemberRoleService,
) {
    private fun requireSadmin(request: HttpServletRequest): Member =
        memberAuth.require(request).also { if (!it.isSadmin) throw ForbiddenException("sadmin만 할 수 있습니다.") }

    @Operation(summary = "관리자 목록 (sadmin)")
    @GetMapping("/api/moderation/members/moderators")
    fun moderators(request: HttpServletRequest): List<AdminMemberResponse> {
        requireSadmin(request)
        return roles.moderators()
    }

    @Operation(summary = "닉네임으로 회원 찾기 (sadmin)")
    @GetMapping("/api/moderation/members")
    fun search(@RequestParam nickname: String, request: HttpServletRequest): List<AdminMemberResponse> {
        requireSadmin(request)
        return roles.search(nickname)
    }

    @Operation(summary = "팀 관리자 지정·해제 (sadmin)", description = "role: MEMBER | TEAM_ADMIN (TEAM_ADMIN이면 club 필수). sadmin 계정은 바꿀 수 없다")
    @PutMapping("/api/moderation/members/{id}/role")
    fun changeRole(@PathVariable id: Long, @RequestBody body: MemberRoleRequest, request: HttpServletRequest): AdminMemberResponse {
        val me = requireSadmin(request)
        return roles.change(id, body, allowed = setOf(MemberRole.MEMBER, MemberRole.TEAM_ADMIN)) { target ->
            if (target.id == me.id) throw ForbiddenException("자기 권한은 바꿀 수 없습니다.")
            if (target.isSadmin) throw ForbiddenException("sadmin 권한은 운영자 API로만 바꿀 수 있습니다.")
        }
    }

    /**
     * nginx `auth_request`가 `/grafana/` 요청마다 부른다. sadmin이면 204 + `X-Factos-User`(Grafana 로그인 이름),
     * 로그인 전이면 401, 그 밖은 403. 응답 본문은 없다
     */
    @GetMapping("/api/auth/stats-access")
    fun statsAccess(request: HttpServletRequest): ResponseEntity<Void> {
        val member = memberAuth.current(request) ?: return ResponseEntity.status(401).build()
        if (!member.isSadmin) return ResponseEntity.status(403).build()
        return ResponseEntity.noContent().header("X-Factos-User", "sadmin-${member.id}").build()
    }
}
