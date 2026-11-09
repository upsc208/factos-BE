package com.atleti.news.moderation

import com.atleti.news.member.auth.AuthProperties
import com.atleti.news.member.auth.MemberAuth
import com.atleti.news.member.comment.Comment
import com.atleti.news.member.comment.CommentRepository
import com.atleti.news.member.domain.*
import com.atleti.news.news.domain.Club
import com.atleti.news.news.domain.NewsArticle
import com.atleti.news.news.domain.NewsArticleRepository
import com.atleti.news.news.domain.TranslationStatus
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
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

/**
 * 댓글 신고: 회원이 신고 → 관리자 목록에 댓글별로 모임 → 삭제·정지하면 RESOLVED, 기각하면 DISMISSED.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = ["atleti.auth.token-secret=test-secret"])
class CommentReportFlowTest {

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var members: MemberRepository
    @Autowired private lateinit var articles: NewsArticleRepository
    @Autowired private lateinit var comments: CommentRepository
    @Autowired private lateinit var reports: CommentReportRepository
    @Autowired private lateinit var props: AuthProperties

    private lateinit var arsenalAdmin: Member
    private lateinit var chelseaAdmin: Member
    private lateinit var troll: Member
    private lateinit var fan1: Member
    private lateinit var fan2: Member
    private var arsenalArticle = 0L

    @BeforeEach
    fun setUp() {
        reports.deleteAll()
        val suffix = UUID.randomUUID().toString()
        val tag = suffix.take(5)
        fun member(name: String, role: MemberRole? = null, club: String? = null) = members.save(
            Member(provider = AuthProvider.KAKAO, providerUserId = "$name-$suffix", nickname = "$name$tag",
                favoriteTeam = FavoriteTeam.OTHER, signupCompleted = true, role = role, adminClub = club)
        )
        arsenalAdmin = member("아관", MemberRole.TEAM_ADMIN, "arsenal")
        chelseaAdmin = member("첼관", MemberRole.TEAM_ADMIN, "chelsea")
        troll = member("악플")
        fan1 = member("팬일")
        fan2 = member("팬이")
        arsenalArticle = articles.save(
            NewsArticle(
                sourceName = "BBC Sport", authorName = "BBC Sport", originalUrl = "https://bbc/$suffix",
                titleKo = "아스날 기사", summaryKo = null, contentKo = null, contentRaw = "body", imageUrl = null,
                publishedAt = OffsetDateTime.now(), category = null, clubs = mutableSetOf(Club.ARSENAL),
                titleEn = "Title", translationStatus = TranslationStatus.DONE,
            )
        ).id!!
    }

    private fun commentBy(member: Member, text: String = "나쁜 말") =
        comments.save(Comment(articleId = arsenalArticle, memberId = member.id!!, content = text)).id!!

    private fun MockHttpServletRequestBuilder.as_(member: Member) =
        cookie(Cookie(MemberAuth.SESSION_COOKIE, props.sessionTokens.issue(member.id!!)))
            .header("Origin", "http://localhost:3000")

    private fun reportReq(commentId: Long, reason: String = "ABUSE", detail: String? = null) =
        post("/api/comments/$commentId/report").contentType(MediaType.APPLICATION_JSON)
            .content(if (detail == null) """{"reason":"$reason"}""" else """{"reason":"$reason","detail":"$detail"}""")

    @Test
    fun `신고는 로그인 필요, 한 사람 한 번, 내 댓글은 안 되고, 기타는 내용이 필요하다`() {
        val c = commentBy(troll)

        mvc.perform(reportReq(c).header("Origin", "http://localhost:3000")).andExpect(status().isUnauthorized)
        mvc.perform(reportReq(c).as_(troll)).andExpect(status().isBadRequest)
        mvc.perform(reportReq(c, reason = "OTHER").as_(fan1)).andExpect(status().isBadRequest)
        mvc.perform(reportReq(c, reason = "NOPE").as_(fan1)).andExpect(status().isBadRequest)
        mvc.perform(reportReq(c).as_(fan1)).andExpect(status().isCreated)
        mvc.perform(reportReq(c).as_(fan1)).andExpect(status().isConflict)

        // 댓글 목록: 신고한 사람에게 reported, 신고 수는 관리자에게만
        mvc.perform(get("/api/news/$arsenalArticle/comments").as_(fan1))
            .andExpect(jsonPath("$.content[0].reported").value(true))
            .andExpect(jsonPath("$.content[0].reportCount").doesNotExist())
        mvc.perform(get("/api/news/$arsenalArticle/comments").as_(fan2))
            .andExpect(jsonPath("$.content[0].reported").value(false))
        mvc.perform(get("/api/news/$arsenalArticle/comments").as_(arsenalAdmin))
            .andExpect(jsonPath("$.content[0].reportCount").value(1))
    }

    @Test
    fun `관리자 목록은 댓글별로 모으고 신고 많은 순, 팀 관리자는 자기 팀 것만`() {
        val many = commentBy(troll, "여러 번 신고된 댓글")
        val once = commentBy(troll, "한 번 신고된 댓글")
        mvc.perform(reportReq(many).as_(fan1)).andExpect(status().isCreated)
        mvc.perform(reportReq(many, reason = "OTHER", detail = "선수 비하").as_(fan2)).andExpect(status().isCreated)
        mvc.perform(reportReq(once, reason = "SPAM").as_(fan1)).andExpect(status().isCreated)

        mvc.perform(get("/api/moderation/reports").as_(arsenalAdmin))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].commentId").value(many))
            .andExpect(jsonPath("$[0].reportCount").value(2))
            .andExpect(jsonPath("$[0].reasons.ABUSE").value(1))
            .andExpect(jsonPath("$[0].reasons.OTHER").value(1))
            .andExpect(jsonPath("$[0].details[0]").value("선수 비하"))
            .andExpect(jsonPath("$[0].authorNickname").value(troll.nickname))
            .andExpect(jsonPath("$[0].articleTitle").value("아스날 기사"))
            .andExpect(jsonPath("$[0].articleClub").value("arsenal"))
        mvc.perform(get("/api/moderation/reports").as_(chelseaAdmin))
            .andExpect(jsonPath("$.length()").value(0))
        mvc.perform(get("/api/moderation/reports").as_(fan1)).andExpect(status().isForbidden)
    }

    @Test
    fun `삭제·정지하면 RESOLVED, 기각하면 DISMISSED로 목록에서 빠진다`() {
        val deleted = commentBy(troll, "지울 댓글")
        val suspended = commentBy(troll, "정지할 댓글")
        val fine = commentBy(troll, "괜찮은 댓글")
        listOf(deleted, suspended, fine).forEach { mvc.perform(reportReq(it).as_(fan1)).andExpect(status().isCreated) }

        mvc.perform(delete("/api/moderation/comments/$deleted").as_(arsenalAdmin)).andExpect(status().isNoContent)
        mvc.perform(
            post("/api/moderation/comments/$suspended/suspend").contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"욕설","days":1,"deleteComment":false}""").as_(arsenalAdmin)
        ).andExpect(status().isCreated)
        mvc.perform(post("/api/moderation/reports/$fine/dismiss").as_(chelseaAdmin)).andExpect(status().isForbidden)
        mvc.perform(post("/api/moderation/reports/$fine/dismiss").as_(arsenalAdmin))
            .andExpect(status().isOk).andExpect(jsonPath("$.closed").value(1))

        mvc.perform(get("/api/moderation/reports").as_(arsenalAdmin)).andExpect(jsonPath("$.length()").value(0))
        val status = reports.findAll().associate { it.commentId to it.status }
        assertThat(status[deleted]).isEqualTo(ReportStatus.RESOLVED)
        assertThat(status[suspended]).isEqualTo(ReportStatus.RESOLVED)
        assertThat(status[fine]).isEqualTo(ReportStatus.DISMISSED)
        assertThat(reports.findAll().first { it.commentId == fine }.handledByNickname).isEqualTo(arsenalAdmin.nickname)
        assertThat(comments.existsById(fine)).isTrue()
    }

    @Test
    fun `작성자가 스스로 지운 댓글의 신고는 목록을 열 때 닫힌다`() {
        val c = commentBy(troll)
        mvc.perform(reportReq(c).as_(fan1)).andExpect(status().isCreated)
        mvc.perform(delete("/api/comments/$c").as_(troll)).andExpect(status().isNoContent)

        mvc.perform(get("/api/moderation/reports").as_(arsenalAdmin))
            .andExpect(jsonPath("$[*].commentId", not(hasItem(c.toInt()))))
        assertThat(reports.findAll().single().status).isEqualTo(ReportStatus.RESOLVED)
    }
}
