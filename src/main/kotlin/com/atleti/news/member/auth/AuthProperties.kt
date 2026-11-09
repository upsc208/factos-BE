package com.atleti.news.member.auth

import com.atleti.news.member.domain.AuthProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** 회원 로그인 설정 (application.properties의 `atleti.auth.*`) */
@Component
class AuthProperties(
    @Value("\${atleti.auth.token-secret:}") val tokenSecret: String,
    @Value("\${atleti.auth.session-days:30}") val sessionDays: Long,
    // 제공자가 인가 코드를 돌려줄 이 서버의 공개 주소. 카카오·구글 콘솔에 등록한 Redirect URI와 같아야 한다
    @Value("\${atleti.auth.callback-base-url:http://localhost:8080}") val callbackBaseUrl: String,
    // 로그인을 마치고 돌아갈 프론트 주소
    @Value("\${atleti.auth.front-base-url:http://localhost:3000}") val frontBaseUrl: String,
    // 프론트·API가 factos.kr / api.factos.kr처럼 갈라져 있으면 ".factos.kr". 비우면 API 호스트 전용 쿠키
    @Value("\${atleti.auth.cookie-domain:}") val cookieDomain: String,
    // 운영(HTTPS)은 true. 로컬 http://localhost에서는 false여야 쿠키가 저장된다
    @Value("\${atleti.auth.cookie-secure:false}") val cookieSecure: Boolean,
    @Value("\${atleti.auth.kakao.client-id:}") kakaoClientId: String,
    @Value("\${atleti.auth.kakao.client-secret:}") kakaoClientSecret: String,
    @Value("\${atleti.auth.google.client-id:}") googleClientId: String,
    @Value("\${atleti.auth.google.client-secret:}") googleClientSecret: String,
    // JWT 서명 키 (EC P-256 JWK를 base64로). 비면 뜰 때마다 임시 키 — 개발용
    @Value("\${atleti.auth.jwt-key:}") private val jwtKey: String = "",
) {
    data class App(val clientId: String, val clientSecret: String) {
        val configured: Boolean get() = clientId.isNotBlank()
    }

    private val apps = mapOf(
        AuthProvider.KAKAO to App(kakaoClientId.trim(), kakaoClientSecret.trim()),
        AuthProvider.GOOGLE to App(googleClientId.trim(), googleClientSecret.trim()),
    )

    fun app(provider: AuthProvider): App = apps.getValue(provider)

    fun callbackUrl(endpoints: OAuthEndpoints): String =
        "${callbackBaseUrl.trimEnd('/')}/api/auth/${endpoints.name.lowercase()}/callback"

    val sessionTokens: SessionTokens by lazy {
        SessionTokens(tokenSecret.trim(), sessionDays * 24 * 3600, if (tokenSecret.isNotBlank()) JwtKeys(jwtKey) else null)
    }
}
