package com.atleti.news.member.auth

import com.atleti.news.common.AllowedOrigins
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 로그인 쿠키로 동작하는 쓰기 요청(댓글·즐겨찾기·로그아웃)은 우리 프론트에서 온 것만 받는다.
 *
 * 쿠키 인증은 브라우저가 쿠키를 자동으로 붙이기 때문에, 다른 사이트가 사용자 몰래 요청을 보내게 할 수 있다(CSRF).
 * SameSite=Lax 쿠키가 1차로 막지만, 같은 사이트의 다른 서브도메인까지는 못 막으므로 Origin도 확인한다.
 * 프론트는 항상 다른 출처(api.)로 fetch하므로 브라우저가 Origin을 반드시 붙인다 — 없으면 거절한다.
 */
@Component
class OriginCheckInterceptor(allowedOrigins: AllowedOrigins) : HandlerInterceptor {

    private val cors = CorsConfiguration().apply { allowedOriginPatterns = allowedOrigins.patterns.toList() }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.method in SAFE_METHODS) return true
        val origin = request.getHeader("Origin")
        if (origin != null && cors.checkOrigin(origin) != null) return true

        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write("""{"error":"허용되지 않은 출처의 요청입니다."}""")
        return false
    }

    companion object {
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
        val PATHS = listOf("/api/auth/logout", "/api/news/*/comments", "/api/comments/**", "/api/me/**", "/api/moderation/**")
    }
}
