package com.atleti.news.member.auth

import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 로그인 시작·콜백의 리다이렉트. 실제 카카오는 부르지 않는다 (state가 틀리면 그 전에 끝난다) */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = ["atleti.auth.token-secret=test-secret", "atleti.auth.kakao.client-id=kakao-rest-key"])
class AuthRedirectTest {

    @Autowired private lateinit var mvc: MockMvc

    @Test
    fun `앱 키가 있는 제공자만 로그인 수단으로 나온다`() {
        mvc.perform(get("/api/auth/providers"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0]").value("kakao"))
    }

    @Test
    fun `로그인 시작은 state 쿠키를 심고 카카오 인가 화면으로 보낸다`() {
        val res = mvc.perform(get("/api/auth/kakao/login").param("returnTo", "/arsenal"))
            .andExpect(status().isFound).andReturn().response

        val location = res.getHeader("Location")!!
        assertThat(location).startsWith("https://kauth.kakao.com/oauth/authorize")
            .contains("client_id=kakao-rest-key")
            .contains("redirect_uri=http://localhost:8080/api/auth/kakao/callback")
        val state = Regex("state=([^&]+)").find(location)!!.groupValues[1]
        val cookie = res.getHeaders("Set-Cookie").first { it.startsWith(MemberAuth.STATE_COOKIE) }
        assertThat(cookie).contains("$state.").contains("HttpOnly").contains("SameSite=Lax")
    }

    @Test
    fun `state가 다르면 로그인하지 않고 실패 표시와 함께 원래 화면으로 돌려보낸다`() {
        val login = mvc.perform(get("/api/auth/kakao/login").param("returnTo", "/chelsea")).andReturn().response
        val stateCookie = login.getCookie(MemberAuth.STATE_COOKIE)!!

        val res = mvc.perform(
            get("/api/auth/kakao/callback").param("code", "c").param("state", "attacker-state")
                .cookie(Cookie(MemberAuth.STATE_COOKIE, stateCookie.value))
        ).andExpect(status().isFound).andReturn().response

        assertThat(res.getHeader("Location")).isEqualTo("http://localhost:3000/chelsea?loginError=1")
        assertThat(res.getHeaders("Set-Cookie")).noneMatch { it.startsWith(MemberAuth.SESSION_COOKIE) }
    }

    @Test
    fun `동의 화면에서 취소하면 실패 표시 없이 돌아간다`() {
        val login = mvc.perform(get("/api/auth/kakao/login").param("returnTo", "/liverpool")).andReturn().response
        val res = mvc.perform(
            get("/api/auth/kakao/callback").param("error", "access_denied")
                .cookie(Cookie(MemberAuth.STATE_COOKIE, login.getCookie(MemberAuth.STATE_COOKIE)!!.value))
        ).andReturn().response

        assertThat(res.getHeader("Location")).isEqualTo("http://localhost:3000/liverpool")
    }
}
