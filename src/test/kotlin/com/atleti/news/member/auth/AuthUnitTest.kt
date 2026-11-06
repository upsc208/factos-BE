package com.atleti.news.member.auth

import com.atleti.news.member.domain.AuthProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class AuthUnitTest {

    private val tokens = SessionTokens("test-secret", ttlSeconds = 3600)
    private val now = Instant.parse("2026-11-05T00:00:00Z")

    @Test
    fun `발급한 토큰은 만료 전까지 회원 id로 풀린다`() {
        val token = tokens.issue(42L, now)
        assertThat(tokens.verify(token, now.plusSeconds(3599))).isEqualTo(42L)
        assertThat(tokens.verify(token, now.plusSeconds(3600))).isNull()
    }

    @Test
    fun `서명이 다르거나 위조된 토큰은 거절한다`() {
        val token = tokens.issue(42L, now)
        assertThat(SessionTokens("other-secret", 3600).verify(token, now)).isNull()

        // 회원 id만 바꿔치기한 토큰
        val forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("1:${now.epochSecond + 3600}".toByteArray())
        assertThat(tokens.verify("$forgedPayload.${token.substringAfter(".")}", now)).isNull()
        assertThat(tokens.verify("garbage", now)).isNull()
        assertThat(tokens.verify(null, now)).isNull()
    }

    @Test
    fun `비밀값이 없으면 로그인이 꺼진다`() {
        val disabled = SessionTokens("", 3600)
        assertThat(disabled.enabled).isFalse()
        assertThat(disabled.verify("a.b", now)).isNull()
    }

    @Test
    fun `로그인 후 돌아갈 경로는 우리 사이트 안의 경로만 받는다`() {
        assertThat(AuthController.safeReturnTo("/arsenal/news/3?fromPage=2")).isEqualTo("/arsenal/news/3?fromPage=2")
        assertThat(AuthController.safeReturnTo("//evil.com")).isEqualTo("/")
        assertThat(AuthController.safeReturnTo("https://evil.com")).isEqualTo("/")
        assertThat(AuthController.safeReturnTo("/\\evil.com")).isEqualTo("/")
        assertThat(AuthController.safeReturnTo(null)).isEqualTo("/")
    }

    @Test
    fun `카카오·구글 사용자 정보에서 회원번호·닉네임·사진을 읽는다`() {
        val mapper = ObjectMapper()
        val kakao = OAuthClient.parseProfile(
            AuthProvider.KAKAO,
            mapper.readTree("""{"id":123456,"kakao_account":{"profile":{"nickname":"구너","profile_image_url":"https://k/img.jpg"}}}"""),
        )
        assertThat(kakao).isEqualTo(OAuthProfile(AuthProvider.KAKAO, "123456", "구너", "https://k/img.jpg"))

        val google = OAuthClient.parseProfile(
            AuthProvider.GOOGLE,
            mapper.readTree("""{"sub":"1029","name":"Kop Fan","picture":"https://g/p.png"}"""),
        )
        assertThat(google).isEqualTo(OAuthProfile(AuthProvider.GOOGLE, "1029", "Kop Fan", "https://g/p.png"))
    }

    @Test
    fun `닉네임 형식 - 2~12자, 한글·영문·숫자·밑줄, 예약어 금지`() {
        val ok = listOf("구너", "Kop_Fan", "spurs2026", "가".repeat(12))
        val bad = listOf("a", "가".repeat(13), "공백 있음", "ㅋㅋㅋ", "emoji⚽", "관리자", "FactosFan", "탈퇴한회원")
        ok.forEach { assertThat(com.atleti.news.member.domain.MemberService.formatError(it)).`as`(it).isNull() }
        bad.forEach { assertThat(com.atleti.news.member.domain.MemberService.formatError(it)).`as`(it).isNotNull() }
    }

    @Test
    fun `닉네임 동의를 안 했으면 임시 닉네임을 붙인다`() {
        val profile = OAuthClient.parseProfile(AuthProvider.KAKAO, ObjectMapper().readTree("""{"id":1,"kakao_account":{}}"""))
        assertThat(profile.nickname).startsWith("팬")
    }
}
