package com.atleti.news.member.auth

import com.atleti.news.member.domain.AuthProvider
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.util.UriComponentsBuilder
import java.time.Duration

/** 제공자에게서 받은 프로필. 이메일은 받지 않는다 */
data class OAuthProfile(
    val provider: AuthProvider,
    val providerUserId: String,
    val nickname: String,
    val profileImageUrl: String?,
)

/** 제공자별 고정값. 앱 키는 [AuthProperties]에서 온다 */
enum class OAuthEndpoints(
    val provider: AuthProvider,
    val authorizeUrl: String,
    val tokenUrl: String,
    val userInfoUrl: String,
    val scope: String,
) {
    // 동의 항목: 닉네임·프로필 사진만 (카카오 개발자 콘솔 > 동의항목에서 켠다)
    KAKAO(
        AuthProvider.KAKAO,
        "https://kauth.kakao.com/oauth/authorize",
        "https://kauth.kakao.com/oauth/token",
        "https://kapi.kakao.com/v2/user/me",
        "profile_nickname profile_image",
    ),
    GOOGLE(
        AuthProvider.GOOGLE,
        "https://accounts.google.com/o/oauth2/v2/auth",
        "https://oauth2.googleapis.com/token",
        "https://openidconnect.googleapis.com/v1/userinfo",
        "openid profile",
    );

    companion object {
        /** URL 경로의 "kakao" / "google" */
        fun fromPath(name: String): OAuthEndpoints? = entries.find { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * OAuth 인가 코드 흐름 (Authorization Code). Spring Security를 쓰지 않고 직접 한다 —
 * 넣으면 기존 REST·WebSocket·관리자 인증 전체에 필터가 걸려서, 댓글 기능 하나 때문에 바뀌는 범위가 너무 크다.
 */
@Component
class OAuthClient(private val props: AuthProperties) {

    private val webClient = WebClient.builder().build()

    fun authorizeUrl(endpoints: OAuthEndpoints, state: String): String {
        val app = props.app(endpoints.provider)
        return UriComponentsBuilder.fromUriString(endpoints.authorizeUrl)
            .queryParam("response_type", "code")
            .queryParam("client_id", app.clientId)
            .queryParam("redirect_uri", props.callbackUrl(endpoints))
            .queryParam("scope", endpoints.scope)
            .queryParam("state", state)
            .encode()
            .build()
            .toUriString()
    }

    /** 인가 코드 → 액세스 토큰 → 프로필. 실패하면 예외 (컨트롤러가 로그인 실패 화면으로 보낸다) */
    fun fetchProfile(endpoints: OAuthEndpoints, code: String): OAuthProfile {
        val app = props.app(endpoints.provider)
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("client_id", app.clientId)
            add("redirect_uri", props.callbackUrl(endpoints))
            add("code", code)
            if (app.clientSecret.isNotBlank()) add("client_secret", app.clientSecret)
        }
        val token = webClient.post().uri(endpoints.tokenUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(form))
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .block(TIMEOUT)
            ?.get("access_token")?.asText()
            ?: error("${endpoints.name} 액세스 토큰 응답이 비었습니다")

        val user = webClient.get().uri(endpoints.userInfoUrl)
            .headers { it.setBearerAuth(token) }
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .block(TIMEOUT)
            ?: error("${endpoints.name} 사용자 정보 응답이 비었습니다")

        return parseProfile(endpoints.provider, user)
    }

    companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val NICKNAME_MAX = 20

        internal fun parseProfile(provider: AuthProvider, json: JsonNode): OAuthProfile = when (provider) {
            // {"id":123,"kakao_account":{"profile":{"nickname":"..","profile_image_url":".."}}}
            AuthProvider.KAKAO -> {
                val profile = json.path("kakao_account").path("profile")
                OAuthProfile(
                    provider = provider,
                    providerUserId = json.path("id").asText().ifBlank { error("카카오 회원번호가 없습니다") },
                    nickname = nicknameOf(profile.path("nickname").asText()),
                    profileImageUrl = profile.path("profile_image_url").asText().ifBlank { null },
                )
            }
            // {"sub":"1029..","name":"..","picture":".."}
            AuthProvider.GOOGLE -> OAuthProfile(
                provider = provider,
                providerUserId = json.path("sub").asText().ifBlank { error("구글 sub가 없습니다") },
                nickname = nicknameOf(json.path("name").asText()),
                profileImageUrl = json.path("picture").asText().ifBlank { null },
            )
        }

        // 닉네임 동의를 안 했거나 비어 있으면 임시 이름. 댓글에 그대로 보이므로 길이를 자른다
        private fun nicknameOf(raw: String): String =
            raw.trim().take(NICKNAME_MAX).ifBlank { "팬${(1000..9999).random()}" }
    }
}
