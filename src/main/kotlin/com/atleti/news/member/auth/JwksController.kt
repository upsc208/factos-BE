package com.atleti.news.member.auth

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * 로그인 JWT의 공개 키. travel-svc 같은 다른 서비스가 이걸 받아 토큰을 직접 검증한다.
 * compose 내부망(`http://backend:8080/.well-known/jwks.json`)으로 부른다 — nginx는 이 경로를 밖에 내지 않는다.
 */
@Tag(name = "Auth API")
@RestController
class JwksController(private val props: AuthProperties) {

    @Operation(summary = "로그인 토큰 공개 키 (JWKS)")
    @GetMapping("/.well-known/jwks.json")
    fun jwks(): ResponseEntity<Map<String, Any>> =
        ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5))).body(props.sessionTokens.publicJwks)
}
