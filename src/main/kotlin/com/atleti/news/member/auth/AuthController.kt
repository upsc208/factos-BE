package com.atleti.news.member.auth

import com.atleti.news.common.NotFoundException
import com.atleti.news.member.domain.Member
import com.atleti.news.member.domain.MemberRepository
import com.atleti.news.member.domain.MemberService
import java.net.URLEncoder
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64

data class MemberResponse(
    val id: Long,
    val nickname: String,
    val profileImageUrl: String?,
    val provider: String,
    val favoriteTeam: String?, // "arsenal" … "other". 가입 마치기 전이면 null
    val signupCompleted: Boolean, // false면 프론트가 "가입 마치기" 화면으로 보낸다
) {
    companion object {
        fun of(m: Member) = MemberResponse(m.id!!, m.nickname, m.profileImageUrl, m.provider.name, m.favoriteTeam?.id, m.signupCompleted)
    }
}

/**
 * 카카오·구글 로그인.
 *
 * 흐름: 프론트가 `/api/auth/{provider}/login?returnTo=/arsenal`로 **이동**(fetch 아님) →
 * 제공자 로그인 화면 → 제공자가 `/callback`으로 돌려보냄 → 회원 저장 + 로그인 쿠키 → 프론트 returnTo로 이동.
 */
@Tag(name = "Auth API", description = "회원 로그인 (카카오·구글)")
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val props: AuthProperties,
    private val oauthClient: OAuthClient,
    private val memberAuth: MemberAuth,
    private val memberRepository: MemberRepository,
    private val memberService: MemberService,
) {
    private val log = LoggerFactory.getLogger(AuthController::class.java)
    private val random = SecureRandom()

    @Operation(summary = "쓸 수 있는 로그인 수단", description = "앱 키가 설정된 제공자만. 프론트는 이걸로 버튼을 그린다")
    @GetMapping("/providers")
    fun providers(): List<String> =
        if (!props.sessionTokens.enabled) emptyList()
        else OAuthEndpoints.entries.filter { props.app(it.provider).configured }.map { it.name.lowercase() }

    @Operation(summary = "로그인 시작 (브라우저 이동용)")
    @GetMapping("/{provider}/login")
    fun login(@PathVariable provider: String, @RequestParam(required = false) returnTo: String?): ResponseEntity<Void> {
        val endpoints = enabledEndpoints(provider)
        val state = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val cookieValue = "$state.${b64(safeReturnTo(returnTo))}"

        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.SET_COOKIE, memberAuth.stateCookie(cookieValue).toString())
            .location(URI.create(oauthClient.authorizeUrl(endpoints, state)))
            .build()
    }

    @Operation(summary = "제공자 콜백 (제공자가 부른다)")
    @GetMapping("/{provider}/callback")
    @Transactional
    fun callback(
        @PathVariable provider: String,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val endpoints = enabledEndpoints(provider)
        val saved = request.cookies?.firstOrNull { it.name == MemberAuth.STATE_COOKIE }?.value.orEmpty()
        val savedState = saved.substringBefore(".")
        val returnTo = safeReturnTo(unb64(saved.substringAfter(".", "")))

        // state가 다르면 우리가 시작한 로그인이 아니다 (다른 사이트가 콜백을 직접 부른 경우)
        if (code.isNullOrBlank() || state.isNullOrBlank() || savedState.isBlank() || state != savedState) {
            if (error == null) log.warn("⚠️ [Auth] {} 콜백 state 불일치 또는 코드 없음", endpoints.name)
            // error가 있으면 사용자가 동의 화면에서 취소한 것 — 조용히 원래 화면으로
            return redirectToFront(returnTo, failed = error == null, clearState = true)
        }

        val member = try {
            upsert(oauthClient.fetchProfile(endpoints, code))
        } catch (e: Exception) {
            log.error("❌ [Auth] {} 로그인 실패: {}", endpoints.name, e.message)
            return redirectToFront(returnTo, failed = true, clearState = true)
        }

        // 가입을 아직 안 마쳤으면(첫 로그인) 닉네임·응원팀을 정하는 화면을 거쳐 원래 화면으로
        val target = if (member.signupCompleted) returnTo else "/signup?returnTo=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8)
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.SET_COOKIE, memberAuth.sessionCookie(member.id!!).toString())
            .header(HttpHeaders.SET_COOKIE, memberAuth.clearStateCookie().toString())
            .location(URI.create(props.frontBaseUrl.trimEnd('/') + target))
            .build()
    }

    @Operation(summary = "내 정보", description = "비로그인이면 401")
    @GetMapping("/me")
    fun me(request: HttpServletRequest): MemberResponse = MemberResponse.of(memberAuth.require(request))

    @Operation(summary = "로그아웃")
    @PostMapping("/logout")
    fun logout(): ResponseEntity<Void> =
        ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, memberAuth.clearSessionCookie().toString()).build()

    private fun enabledEndpoints(provider: String): OAuthEndpoints {
        val endpoints = OAuthEndpoints.fromPath(provider) ?: throw NotFoundException("알 수 없는 로그인 수단입니다: $provider")
        if (!props.app(endpoints.provider).configured || !props.sessionTokens.enabled) {
            throw NotFoundException("$provider 로그인이 설정되어 있지 않습니다")
        }
        return endpoints
    }

    private fun upsert(profile: OAuthProfile): Member {
        val existing = memberRepository.findByProviderAndProviderUserId(profile.provider, profile.providerUserId)
        if (existing != null) {
            // 닉네임은 처음 가입 때 것을 유지한다 (나중에 닉네임 변경 기능을 붙이면 덮어쓰면 안 되므로). 사진만 갱신
            existing.profileImageUrl = profile.profileImageUrl
            existing.lastLoginAt = OffsetDateTime.now()
            return memberRepository.save(existing)
        }
        log.info("🆕 [Auth] 새 회원: {}", profile.provider)
        // 카카오·구글 닉네임은 중복·금칙어 검사를 안 거쳤으므로 쓰지 않는다. 임시 닉네임으로 만들고
        // 가입 마치기 화면에서 본인이 정한다
        return memberRepository.save(
            Member(
                provider = profile.provider,
                providerUserId = profile.providerUserId,
                nickname = memberService.provisionalNickname(),
                profileImageUrl = profile.profileImageUrl,
            )
        )
    }

    private fun redirectToFront(returnTo: String, failed: Boolean, clearState: Boolean): ResponseEntity<Void> {
        val target = if (failed) returnTo + (if ('?' in returnTo) "&" else "?") + "loginError=1" else returnTo
        return ResponseEntity.status(HttpStatus.FOUND)
            .apply { if (clearState) header(HttpHeaders.SET_COOKIE, memberAuth.clearStateCookie().toString()) }
            .location(URI.create(props.frontBaseUrl.trimEnd('/') + target))
            .build()
    }

    companion object {
        /**
         * 로그인 후 돌아갈 경로. 우리 프론트 안의 경로만 받는다 — 아니면 로그인 링크를 이용해
         * 피싱 사이트로 보내는 오픈 리다이렉트가 된다. "//evil.com"은 브라우저가 외부 주소로 읽으므로 막는다.
         */
        internal fun safeReturnTo(raw: String?): String {
            val path = raw?.trim().orEmpty()
            val ok = path.startsWith("/") && !path.startsWith("//") && '\\' !in path &&
                path.length <= 300 && path.none { it.isISOControl() }
            return if (ok) path else "/"
        }

        private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(StandardCharsets.UTF_8))
        private fun unb64(s: String) =
            runCatching { String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8) }.getOrNull()
    }
}
