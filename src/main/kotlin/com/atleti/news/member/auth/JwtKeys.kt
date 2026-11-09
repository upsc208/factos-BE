package com.atleti.news.member.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * 로그인 토큰(JWT, ES256) 서명 키. **비밀 키는 이 서비스만** 갖고, 공개 키는 JWKS(`/.well-known/jwks.json`)로 내준다.
 * 다른 서비스(travel-svc …)는 공개 키로 토큰을 직접 검증한다 — 비밀값을 나눠 갖지 않는다.
 *
 * 키는 EC P-256 JWK(JSON)를 base64로 감싼 값 (`AUTH_JWT_KEY`). 만드는 법: infra/compose/README.md
 * 비어 있으면 뜰 때마다 새 키를 만든다 — 재시작하면 모든 로그인이 풀리므로 개발용이다.
 */
class JwtKeys(encodedJwk: String) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val key: ECKey = if (encodedJwk.isNotBlank()) {
        ECKey.parse(String(Base64.getDecoder().decode(encodedJwk.trim()))).let { k ->
            require(k.isPrivate && k.curve == Curve.P_256) { "AUTH_JWT_KEY는 P-256 비밀 키 JWK여야 합니다" }
            if (k.keyID != null) k else ECKey.Builder(k).keyIDFromThumbprint().build()
        }
    } else {
        log.warn("⚠️ [Auth] AUTH_JWT_KEY가 없어 임시 서명 키를 만듭니다 — 재시작하면 로그인이 모두 풀립니다 (개발용)")
        ECKeyGenerator(Curve.P_256).keyIDFromThumbprint(true).generate()
    }
    private val signer = ECDSASigner(key)
    private val verifier = ECDSAVerifier(key.toPublicJWK())

    /** 공개 키만 담긴 JWKS (JSON 객체) */
    val publicJwks: Map<String, Any> = JWKSet(key.toPublicJWK()).toJSONObject(true)

    fun sign(memberId: Long, now: Instant, expiresAt: Instant): String =
        SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(com.nimbusds.jose.JOSEObjectType.JWT).build(),
            JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(memberId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .build(),
        ).apply { sign(signer) }.serialize()

    /** @return 서명·발급자·만료가 맞으면 회원 id */
    fun verify(token: String, now: Instant): Long? = runCatching {
        val jwt = SignedJWT.parse(token)
        if (jwt.header.algorithm != JWSAlgorithm.ES256 || !jwt.verify(verifier)) return null
        val claims = jwt.jwtClaimsSet
        if (claims.issuer != ISSUER) return null
        if (claims.expirationTime == null || !claims.expirationTime.toInstant().isAfter(now)) return null
        claims.subject?.toLongOrNull()
    }.getOrNull()

    companion object {
        /** 다른 서비스가 확인하는 발급자 값 */
        const val ISSUER = "factos"
    }
}
