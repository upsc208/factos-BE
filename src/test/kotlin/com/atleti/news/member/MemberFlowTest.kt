package com.atleti.news.member

import com.atleti.news.member.auth.AuthProperties
import com.atleti.news.member.auth.MemberAuth
import com.atleti.news.member.bookmark.BookmarkRepository
import com.atleti.news.member.comment.Comment
import com.atleti.news.member.comment.CommentRepository
import com.atleti.news.member.domain.AuthProvider
import com.atleti.news.member.domain.FavoriteTeam
import com.atleti.news.member.domain.Member
import com.atleti.news.member.domain.MemberRepository
import com.atleti.news.member.domain.MemberService
import com.atleti.news.news.domain.NewsArticle
import com.atleti.news.news.domain.NewsArticleRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.util.UUID

/** 가입 마치기(닉네임·응원팀), 닉네임 중복, 탈퇴 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = ["atleti.auth.token-secret=test-secret"])
class MemberFlowTest {

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var members: MemberRepository
    @Autowired private lateinit var memberService: MemberService
    @Autowired private lateinit var articles: NewsArticleRepository
    @Autowired private lateinit var comments: CommentRepository
    @Autowired private lateinit var bookmarks: BookmarkRepository
    @Autowired private lateinit var props: AuthProperties

    private lateinit var newbie: Member // 방금 첫 로그인 — 가입 마치기 전
    private lateinit var veteran: Member // 가입 끝난 회원
    private lateinit var tag: String
    private var articleId = 0L

    @BeforeEach
    fun setUp() {
        tag = UUID.randomUUID().toString().take(6)
        newbie = members.save(Member(provider = AuthProvider.KAKAO, providerUserId = "n-$tag", nickname = memberService.provisionalNickname()))
        veteran = members.save(
            Member(provider = AuthProvider.GOOGLE, providerUserId = "v-$tag", nickname = "고참$tag", favoriteTeam = FavoriteTeam.CHELSEA, signupCompleted = true)
        )
        articleId = articles.save(
            NewsArticle(
                sourceName = "BBC Sport", originalUrl = "https://bbc/m-$tag", titleKo = "제목", summaryKo = null,
                contentKo = null, contentRaw = null, imageUrl = null, publishedAt = OffsetDateTime.now(),
            )
        ).id!!
    }

    private fun MockHttpServletRequestBuilder.as_(member: Member) =
        cookie(Cookie(MemberAuth.SESSION_COOKIE, props.sessionTokens.issue(member.id!!)))
            .header("Origin", "http://localhost:3000")

    private fun profile(nickname: String, team: String?) = put("/api/me/profile")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"nickname":"$nickname","favoriteTeam":${team?.let { "\"$it\"" } ?: "null"}}""")

    @Test
    fun `가입 마치기 전에는 댓글·즐겨찾기를 못 쓰고, 닉네임·팀을 정하면 쓸 수 있다`() {
        mvc.perform(get("/api/auth/me").as_(newbie)).andExpect(jsonPath("$.signupCompleted").value(false))
        mvc.perform(post("/api/news/$articleId/comments").as_(newbie).contentType(MediaType.APPLICATION_JSON).content("""{"content":"hi"}"""))
            .andExpect(status().isForbidden)
        mvc.perform(put("/api/me/bookmarks/$articleId").as_(newbie)).andExpect(status().isForbidden)

        mvc.perform(profile("새내기$tag", "tottenham").as_(newbie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nickname").value("새내기$tag"))
            .andExpect(jsonPath("$.favoriteTeam").value("tottenham"))
            .andExpect(jsonPath("$.signupCompleted").value(true))

        mvc.perform(post("/api/news/$articleId/comments").as_(newbie).contentType(MediaType.APPLICATION_JSON).content("""{"content":"hi"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.favoriteTeam").value("tottenham"))
    }

    @Test
    fun `닉네임 중복 확인 - 대소문자만 달라도 중복, 내 닉네임은 사용 가능`() {
        members.save(Member(provider = AuthProvider.KAKAO, providerUserId = "g-$tag", nickname = "Gunner$tag", signupCompleted = true, favoriteTeam = FavoriteTeam.ARSENAL))

        mvc.perform(get("/api/members/nickname-check").param("nickname", "gunner$tag"))
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.reason").value("이미 쓰고 있는 닉네임입니다."))
        mvc.perform(get("/api/members/nickname-check").param("nickname", "다른이름$tag"))
            .andExpect(jsonPath("$.available").value(true))
        mvc.perform(get("/api/members/nickname-check").param("nickname", veteran.nickname).as_(veteran))
            .andExpect(jsonPath("$.available").value(true))
    }

    @Test
    fun `이미 있는 닉네임으로 저장하면 409, 형식이 틀리거나 팀이 없으면 400`() {
        mvc.perform(profile(veteran.nickname.uppercase(), "arsenal").as_(newbie)).andExpect(status().isConflict)
        mvc.perform(profile("a", "arsenal").as_(newbie)).andExpect(status().isBadRequest)
        mvc.perform(profile("관리자$tag", "arsenal").as_(newbie)).andExpect(status().isBadRequest)
        mvc.perform(profile("괜찮은$tag", null).as_(newbie)).andExpect(status().isBadRequest)
        mvc.perform(profile("괜찮은$tag", "barcelona").as_(newbie)).andExpect(status().isBadRequest)
    }

    @Test
    fun `탈퇴하면 회원·즐겨찾기는 지워지고 댓글은 탈퇴한 회원으로 남는다`() {
        comments.save(Comment(articleId = articleId, memberId = veteran.id!!, content = "남는 댓글"))
        mvc.perform(put("/api/me/bookmarks/$articleId").as_(veteran)).andExpect(status().isOk)

        val res = mvc.perform(delete("/api/me").as_(veteran)).andExpect(status().isNoContent).andReturn().response
        assertThat(res.getHeader("Set-Cookie")).contains("${MemberAuth.SESSION_COOKIE}=;").contains("Max-Age=0")

        assertThat(members.findById(veteran.id!!)).isEmpty
        assertThat(bookmarks.existsByMemberIdAndArticleId(veteran.id!!, articleId)).isFalse()
        mvc.perform(get("/api/news/$articleId/comments"))
            .andExpect(jsonPath("$.content[0].content").value("남는 댓글"))
            .andExpect(jsonPath("$.content[0].nickname").value("탈퇴한 회원"))
        // 지운 회원의 쿠키가 남아 있어도 로그인으로 치지 않는다
        mvc.perform(get("/api/auth/me").as_(veteran)).andExpect(status().isUnauthorized)
        // 탈퇴하면 닉네임이 풀려 다른 사람이 쓸 수 있다
        mvc.perform(get("/api/members/nickname-check").param("nickname", veteran.nickname)).andExpect(jsonPath("$.available").value(true))
    }

    @Test
    fun `탈퇴는 허용 출처에서만`() {
        mvc.perform(delete("/api/me").cookie(Cookie(MemberAuth.SESSION_COOKIE, props.sessionTokens.issue(veteran.id!!))))
            .andExpect(status().isForbidden)
        assertThat(members.findById(veteran.id!!)).isPresent
    }
}
