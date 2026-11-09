package com.atleti.news.member.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 로그인 쿠키에 넣는 서명 토큰.
 *
 * - **지금**: JWT(ES256, [JwtKeys]). 다른 서비스(travel-svc)가 공개 키(JWKS)로 직접 검증한다 → 한 번 로그인으로 모든 서비스
 * - **예전**: `base64(memberId:만료초).HMAC` — 이미 나간 쿠키가 만료될 때까지(최대 [ttlSeconds]) 계속 받아 준다.
 *   새로 발급하지는 않는다. 다음 로그인부터 JWT가 된다
 *
 * 서버에 세션을 저장하지 않는다. Redis에 두면 Redis가 내려갔을 때 로그인도 같이 죽는다 —
 * 이 앱은 Redis 없이도 뜨게(fail-open) 만들어져 있어서 그 원칙을 따른다.
 * 대신 개별 로그아웃 강제(토큰 폐기)는 못 한다. 필요해지면 [secret]을 바꾸면 전원 로그아웃된다.
 */
class SessionTokens(
    private val secret: String,
    private val ttlSeconds: Long,
    private val jwt: JwtKeys? = null,
) {

    val enabled: Boolean get() = secret.isNotBlank()

    fun issue(memberId: Long, now: Instant = Instant.now()): String {
        check(enabled) { "atleti.auth.token-secret이 비어 있어 로그인 토큰을 만들 수 없습니다" }
        jwt?.let { return it.sign(memberId, now, now.plusSeconds(ttlSeconds)) }
        val payload = encode("$memberId:${now.epochSecond + ttlSeconds}")
        return "$payload.${sign(payload)}"
    }

    /** @return 유효하면 회원 id, 아니면 null (서명 불일치·만료·형식 오류 전부) */
    fun verify(token: String?, now: Instant = Instant.now()): Long? {
        if (!enabled || token.isNullOrBlank()) return null
        if (jwt != null && token.count { it == '.' } == 2) return jwt.verify(token, now)
        val parts = token.split(".")
        if (parts.size != 2) return null
        if (!MessageDigest.isEqual(parts[1].toByteArray(), sign(parts[0]).toByteArray())) return null

        val (id, expiresAt) = decode(parts[0]).split(":").takeIf { it.size == 2 } ?: return null
        val exp = expiresAt.toLongOrNull() ?: return null
        if (now.epochSecond >= exp) return null
        return id.toLongOrNull()
    }

    /** 공개 키 JWKS — 다른 서비스가 토큰을 검증할 때 받는다 */
    val publicJwks: Map<String, Any> get() = jwt?.publicJwks ?: mapOf("keys" to emptyList<Any>())

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        // 관리자 토큰과 비밀값을 같이 써도 서로의 토큰이 통하지 않게 용도 접두어를 붙인다
        mac.init(SecretKeySpec("member:$secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun encode(raw: String) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))

    private fun decode(encoded: String) =
        runCatching { String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8) }.getOrDefault("")
}
