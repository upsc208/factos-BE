package com.atleti.news.event

import com.atleti.news.member.auth.AuthProperties
import com.atleti.news.member.auth.MemberAuth
import com.atleti.news.member.comment.Comment
import com.atleti.news.member.comment.CommentRepository
import com.atleti.news.member.domain.*
import com.atleti.news.news.domain.Club
import com.atleti.news.news.domain.NewsArticle
import com.atleti.news.news.domain.NewsArticleRepository
import com.atleti.news.news.domain.TranslationStatus
import com.fasterxml.jackson.databind.ObjectMapper
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

/** 회원이 바뀌면 다른 서비스가 알 수 있게 member.* 이벤트가 아웃박스에 적힌다 + JWKS 공개 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = ["atleti.auth.token-secret=test-secret"])
class MemberEventsTest {

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var members: MemberRepository
    @Autowired private lateinit var memberService: MemberService
    @Autowired private lateinit var outbox: OutboxEventRepository
    @Autowired private lateinit var articles: NewsArticleRepository
    @Autowired private lateinit var comments: CommentRepository
    @Autowired private lateinit var props: AuthProperties
    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var tag: String

    @BeforeEach
    fun setUp() {
        outbox.deleteAll()
        tag = UUID.randomUUID().toString().take(6)
    }

    private fun member(name: String, role: MemberRole? = null, club: String? = null, completed: Boolean = true) = members.save(
        Member(provider = AuthProvider.KAKAO, providerUserId = "$name-$tag", nickname = "$name$tag",
            favoriteTeam = FavoriteTeam.OTHER, signupCompleted = completed, role = role, adminClub = club)
    )

    private fun MockHttpServletRequestBuilder.as_(m: Member) =
        cookie(Cookie(MemberAuth.SESSION_COOKIE, props.sessionTokens.issue(m.id!!))).header("Origin", "http://localhost:3000")

    private fun payloads(type: String) = outbox.findAllByEventTypeOrderByIdAsc(type).map { objectMapper.readTree(it.payload) }

    @Test
    fun `가입 마치기·탈퇴가 member 이벤트로 적힌다`() {
        val m = member("새내기", completed = false)
        mvc.perform(
            put("/api/me/profile").as_(m).contentType(MediaType.APPLICATION_JSON)
                .content("""{"nickname":"팬$tag","favoriteTeam":"arsenal"}""")
        ).andExpect(status().isOk)

        val updated = payloads(MemberEvents.UPDATED).single()
        assertThat(updated["memberId"].asLong()).isEqualTo(m.id)
        assertThat(updated["nickname"].asText()).isEqualTo("팬$tag")
        assertThat(updated["favoriteTeam"].asText()).isEqualTo("arsenal")
        assertThat(updated["signupCompleted"].asBoolean()).isTrue()
        assertThat(updated["role"].asText()).isEqualTo("MEMBER")
        assertThat(updated["suspendedUntil"].isNull).isTrue()

        mvc.perform(delete("/api/me").as_(m)).andExpect(status().isNoContent)
        assertThat(payloads(MemberEvents.DELETED).single()["memberId"].asLong()).isEqualTo(m.id)
    }

    @Test
    fun `정지·해제도 다른 서비스에 알린다`() {
        val admin = member("총관", MemberRole.ADMIN)
        val troll = member("악플")
        val articleId = articles.save(
            NewsArticle(
                sourceName = "BBC Sport", authorName = "BBC Sport", originalUrl = "https://bbc/$tag", titleKo = null, summaryKo = null,
                contentKo = null, contentRaw = "b", imageUrl = null, publishedAt = OffsetDateTime.now(), category = null,
                clubs = mutableSetOf(Club.ARSENAL), titleEn = "t", translationStatus = TranslationStatus.PENDING,
            )
        ).id!!
        val c = comments.save(Comment(articleId = articleId, memberId = troll.id!!, content = "나쁜 말")).id!!

        val suspensionId = objectMapper.readTree(
            mvc.perform(
                post("/api/moderation/comments/$c/suspend").as_(admin).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":"욕설","days":3,"deleteComment":true}""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
        )["id"].asLong()
        val suspended = payloads(MemberEvents.UPDATED).last()
        assertThat(suspended["memberId"].asLong()).isEqualTo(troll.id)
        assertThat(suspended["suspendedUntil"].isNull).isFalse()
        assertThat(suspended["suspensionReason"].asText()).isEqualTo("욕설")

        mvc.perform(post("/api/moderation/suspensions/$suspensionId/lift").as_(admin)).andExpect(status().isOk)
        val lifted = payloads(MemberEvents.UPDATED).last()
        assertThat(lifted["memberId"].asLong()).isEqualTo(troll.id)
        assertThat(lifted["suspendedUntil"].isNull).isTrue()
    }

    @Test
    fun `로그인 쿠키는 JWT이고 JWKS로 공개 키를 내준다`() {
        val m = member("팬")
        assertThat(props.sessionTokens.issue(m.id!!).count { it == '.' }).isEqualTo(2)
        mvc.perform(get("/api/auth/me").as_(m)).andExpect(status().isOk).andExpect(jsonPath("$.id").value(m.id!!))
        mvc.perform(get("/.well-known/jwks.json"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.keys[0].kty").value("EC"))
            .andExpect(jsonPath("$.keys[0].d").doesNotExist())
    }
}
