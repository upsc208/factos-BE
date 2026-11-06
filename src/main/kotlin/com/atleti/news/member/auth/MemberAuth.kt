package com.atleti.news.member.auth

import com.atleti.news.common.ForbiddenException
import com.atleti.news.common.UnauthorizedException
import com.atleti.news.member.domain.Member
import com.atleti.news.member.domain.MemberRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 요청의 로그인 쿠키 → 회원. 컨트롤러가 직접 부른다:
 * - [current]: 비로그인이어도 되는 곳 (댓글 목록의 "내 댓글" 표시)
 * - [require]: 로그인이 꼭 필요한 곳 (댓글 쓰기, 즐겨찾기) — 없으면 401
 */
@Component
class MemberAuth(
    private val props: AuthProperties,
    private val memberRepository: MemberRepository,
) {
    fun current(request: HttpServletRequest): Member? {
        val token = request.cookies?.firstOrNull { it.name == SESSION_COOKIE }?.value
        val memberId = props.sessionTokens.verify(token) ?: return null
        return memberRepository.findById(memberId).orElse(null)
    }

    fun require(request: HttpServletRequest): Member = current(request) ?: throw UnauthorizedException()

    /** 로그인 + 가입 마치기(닉네임·응원팀)까지 끝난 회원. 댓글·즐겨찾기는 이걸 쓴다 */
    fun requireCompleted(request: HttpServletRequest): Member {
        val member = require(request)
        if (!member.signupCompleted) throw ForbiddenException("닉네임과 응원팀을 정하고 가입을 마쳐 주세요.")
        return member
    }

    fun sessionCookie(memberId: Long): ResponseCookie =
        cookie(SESSION_COOKIE, props.sessionTokens.issue(memberId), "/", Duration.ofDays(props.sessionDays))

    fun clearSessionCookie(): ResponseCookie = cookie(SESSION_COOKIE, "", "/", Duration.ZERO)

    /** 로그인 시작 ~ 콜백 사이에만 쓰는 state 쿠키 (CSRF 방지). 콜백 경로에만 보낸다 */
    fun stateCookie(value: String): ResponseCookie = cookie(STATE_COOKIE, value, "/api/auth", Duration.ofMinutes(10))

    fun clearStateCookie(): ResponseCookie = cookie(STATE_COOKIE, "", "/api/auth", Duration.ZERO)

    // SameSite=Lax: 다른 사이트에서 보낸 POST/DELETE에는 쿠키가 안 붙는다.
    // 프론트와 API가 같은 사이트(factos.kr / api.factos.kr, 또는 localhost)여야 fetch에 쿠키가 실린다
    private fun cookie(name: String, value: String, path: String, maxAge: Duration): ResponseCookie =
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(props.cookieSecure)
            .sameSite("Lax")
            .path(path)
            .maxAge(maxAge)
            .apply { if (props.cookieDomain.isNotBlank()) domain(props.cookieDomain) }
            .build()

    companion object {
        const val SESSION_COOKIE = "factos_session"
        const val STATE_COOKIE = "factos_oauth_state"
    }
}
