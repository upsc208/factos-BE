package com.atleti.news.moderation

import com.atleti.news.member.auth.AuthProperties
import com.atleti.news.member.auth.MemberAuth
import com.atleti.news.member.domain.*
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * 권한 세 단계: sadmin(ADMIN) · admin(TEAM_ADMIN) · user.
 * sadmin만 통계(Grafana) 출입·팀 관리자 지정. sadmin 자신과 다른 sadmin은 사이트에서 못 바꾼다
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = ["atleti.auth.token-secret=test-secret", "atleti.admin.key=test-admin-key"])
class SadminTest {

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var members: MemberRepository
    @Autowired private lateinit var props: AuthProperties

    private lateinit var sadmin: Member
    private lateinit var otherSadmin: Member
    private lateinit var teamAdmin: Member
    private lateinit var user: Member
    private lateinit var tag: String

    @BeforeEach
    fun setUp() {
        val suffix = UUID.randomUUID().toString()
        tag = suffix.take(6)
        fun member(name: String, role: MemberRole? = null, club: String? = null) = members.save(
            Member(provider = AuthProvider.KAKAO, providerUserId = "$name-$suffix", nickname = "$name$tag",
                favoriteTeam = FavoriteTeam.OTHER, signupCompleted = true, role = role, adminClub = club)
        )
        sadmin = member("개발자", MemberRole.ADMIN)
        otherSadmin = member("운영자", MemberRole.ADMIN)
        teamAdmin = member("아관", MemberRole.TEAM_ADMIN, "arsenal")
        user = member("팬")
    }

    private fun MockHttpServletRequestBuilder.as_(member: Member) =
        cookie(Cookie(MemberAuth.SESSION_COOKIE, props.sessionTokens.issue(member.id!!)))
            .header("Origin", "http://localhost:3000")

    private fun role(target: Member, body: String, by: Member) =
        mvc.perform(put("/api/moderation/members/${target.id}/role").as_(by).contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `통계 출입은 sadmin만 - nginx auth_request용`() {
        mvc.perform(get("/api/auth/stats-access")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/auth/stats-access").as_(user)).andExpect(status().isForbidden)
        mvc.perform(get("/api/auth/stats-access").as_(teamAdmin)).andExpect(status().isForbidden)
        mvc.perform(get("/api/auth/stats-access").as_(sadmin))
            .andExpect(status().isNoContent)
            .andExpect(header().string("X-Factos-User", "sadmin-${sadmin.id}"))
    }

    @Test
    fun `sadmin이 팀 관리자를 지정하고 해제한다`() {
        role(user, """{"role":"TEAM_ADMIN","club":"chelsea"}""", sadmin)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.role").value("TEAM_ADMIN"))
            .andExpect(jsonPath("$.adminClub").value("chelsea"))
        assertThat(members.findById(user.id!!).get().adminClub).isEqualTo("chelsea")

        mvc.perform(get("/api/moderation/members/moderators").as_(sadmin))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.nickname == '팬$tag')].adminClub").value("chelsea"))

        role(user, """{"role":"MEMBER"}""", sadmin).andExpect(status().isOk).andExpect(jsonPath("$.role").value("MEMBER"))
        assertThat(members.findById(user.id!!).get().role).isNull()
    }

    @Test
    fun `sadmin은 사이트에서 sadmin을 만들거나 바꾸지 못한다`() {
        role(user, """{"role":"ADMIN"}""", sadmin).andExpect(status().isBadRequest)
        role(otherSadmin, """{"role":"MEMBER"}""", sadmin).andExpect(status().isForbidden)
        role(sadmin, """{"role":"TEAM_ADMIN","club":"arsenal"}""", sadmin).andExpect(status().isForbidden)
        role(user, """{"role":"TEAM_ADMIN"}""", sadmin).andExpect(status().isBadRequest) // 팀 없음
        assertThat(members.findById(otherSadmin.id!!).get().role).isEqualTo(MemberRole.ADMIN)
    }

    @Test
    fun `팀 관리자와 일반 회원은 권한 API를 못 쓴다`() {
        role(user, """{"role":"TEAM_ADMIN","club":"arsenal"}""", teamAdmin).andExpect(status().isForbidden)
        role(teamAdmin, """{"role":"TEAM_ADMIN","club":"chelsea"}""", user).andExpect(status().isForbidden)
        mvc.perform(get("/api/moderation/members").param("nickname", "팬").as_(teamAdmin)).andExpect(status().isForbidden)
        mvc.perform(get("/api/moderation/members/moderators")).andExpect(status().isUnauthorized)
        assertThat(members.findById(user.id!!).get().role).isNull()
    }

    @Test
    fun `운영자 API는 그대로 sadmin도 만든다`() {
        mvc.perform(put("/api/admin/members/${user.id}/role").header("X-Admin-Key", "test-admin-key")
            .contentType(MediaType.APPLICATION_JSON).content("""{"role":"ADMIN"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.role").value("ADMIN"))
    }
}
