package com.atleti.news.member.auth

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64

/** 로그인 토큰: JWT(ES256)로 발급하고, 예전 HMAC 토큰도 만료 전까지 받아 준다 */
class SessionJwtTest {

    private val now = Instant.parse("2026-11-09T00:00:00Z")
    private fun keyB64() = Base64.getEncoder().encodeToString(ECKeyGenerator(Curve.P_256).generate().toJSONString().toByteArray())

    private val keys = JwtKeys(keyB64())
    private val tokens = SessionTokens("test-secret", 3600, keys)

    @Test
    fun `JWT로 발급하고 검증한다 — 발급자·주체·만료가 들어 있다`() {
        val token = tokens.issue(42, now)

        val jwt = SignedJWT.parse(token)
        assertThat(jwt.header.algorithm.name).isEqualTo("ES256")
        assertThat(jwt.header.keyID).isNotBlank()
        assertThat(jwt.jwtClaimsSet.issuer).isEqualTo(JwtKeys.ISSUER)
        assertThat(jwt.jwtClaimsSet.subject).isEqualTo("42")
        assertThat(tokens.verify(token, now)).isEqualTo(42)
        assertThat(tokens.verify(token, now.plusSeconds(3600))).isNull() // 만료
    }

    @Test
    fun `다른 키로 서명했거나 내용을 바꾼 토큰은 거절한다`() {
        val other = SessionTokens("test-secret", 3600, JwtKeys(keyB64())).issue(42, now)
        assertThat(tokens.verify(other, now)).isNull()

        val (h, p, s) = tokens.issue(42, now).split(".")
        val forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
            String(Base64.getUrlDecoder().decode(p)).replace("\"42\"", "\"1\"").toByteArray()
        )
        assertThat(tokens.verify("$h.$forged.$s", now)).isNull()
    }

    @Test
    fun `예전 HMAC 토큰은 만료 전까지 계속 받는다 (배포해도 로그아웃되지 않게)`() {
        val legacy = SessionTokens("test-secret", 3600).issue(7, now)
        assertThat(legacy.count { it == '.' }).isEqualTo(1)
        assertThat(tokens.verify(legacy, now)).isEqualTo(7)
    }

    @Test
    fun `JWKS에는 공개 키만 나간다`() {
        @Suppress("UNCHECKED_CAST")
        val key = (keys.publicJwks["keys"] as List<Map<String, Any>>).single()
        assertThat(key["kty"]).isEqualTo("EC")
        assertThat(key["crv"]).isEqualTo("P-256")
        assertThat(key).containsKeys("x", "y", "kid").doesNotContainKey("d")
    }
}
