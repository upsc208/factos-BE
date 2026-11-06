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
import org.hamcrest.Matchers.containsString
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
 * 관리자 기능: 팀 관리자 범위, 댓글 삭제, 사유 있는 임시 정지, 정지 목록·해제, 운영자의 권한 부여.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = ["atleti.auth.token-secret=test-secret", "atleti.admin.key=test-admin-key"])
class ModerationFlowTest {

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var members: MemberRepository
    @Autowired private lateinit var articles: NewsArticleRepository
    @Autowired private lateinit var comments: CommentRepository
    @Autowired private lateinit var suspensions: SuspensionRepository
    @Autowired private lateinit var props: AuthProperties
    @Autowired private lateinit var memberService: MemberService

    private lateinit var arsenalAdmin: Member
    private lateinit var chelseaAdmin: Member
    private lateinit var superAdmin: Member
    private lateinit var troll: Member
    private lateinit var fan: Member
    private var arsenalArticle = 0L
    private var chelseaArticle = 0L
    private lateinit var tag: String

    @BeforeEach
    fun setUp() {
        val suffix = UUID.randomUUID().toString()
        tag = suffix.take(5)
        fun member(name: String, role: MemberRole? = null, club: String? = null) = members.save(
            Member(provider = AuthProvider.KAKAO, providerUserId = "$name-$suffix", nickname = "$name$tag",
                favoriteTeam = FavoriteTeam.OTHER, signupCompleted = true, role = role, adminClub = club)
        )
        arsenalAdmin = member("아관", MemberRole.TEAM_ADMIN, "arsenal")
        chelseaAdmin = member("첼관", MemberRole.TEAM_ADMIN, "chelsea")
        superAdmin = member("총관", MemberRole.ADMIN)
        troll = member("악플")
        fan = member("팬")
        arsenalArticle = article(suffix + "a", Club.ARSENAL)
        chelseaArticle = article(suffix + "c", Club.CHELSEA)
    }

    private fun article(key: String, club: Club) = articles.save(
        NewsArticle(
            sourceName = "BBC Sport", authorName = "BBC Sport", originalUrl = "https://bbc/$key",
            titleKo = null, summaryKo = null, contentKo = null, contentRaw = "body", imageUrl = null,
            publishedAt = OffsetDateTime.now(), category = null, clubs = mutableSetOf(club),
            titleEn = "Title", translationStatus = TranslationStatus.PENDING,
        )
    ).id!!

    private fun commentBy(member: Member, articleId: Long, text: String = "나쁜 말") =
        comments.save(Comment(articleId = articleId, memberId = member.id!!, content = text)).id!!

    private fun MockHttpServletRequestBuilder.as_(member: Member) =
        cookie(Cookie(MemberAuth.SESSION_COOKIE, props.sessionTokens.issue(member.id!!)))
            .header("Origin", "http://localhost:3000")

    private fun suspendReq(commentId: Long, reason: String = "욕설", days: Int = 3, delete: Boolean = true) =
        post("/api/moderation/comments/$commentId/suspend").contentType(MediaType.APPLICATION_JSON)
            .content("""{"reason":"$reason","days":$days,"deleteComment":$delete}""")

    @Test
    fun `팀 관리자는 자기 팀 기사 댓글만 지운다, 일반 회원은 못 지운다`() {
        val onArsenal = commentBy(troll, arsenalArticle)
        val onChelsea = commentBy(troll, chelseaArticle)

        mvc.perform(delete("/api/moderation/comments/$onArsenal").as_(fan)).andExpect(status().isForbidden)
        mvc.perform(delete("/api/moderation/comments/$onChelsea").as_(arsenalAdmin)).andExpect(status().isForbidden)
        mvc.perform(delete("/api/moderation/comments/$onArsenal").as_(arsenalAdmin)).andExpect(status().isNoContent)
        mvc.perform(delete("/api/moderation/comments/$onChelsea").as_(superAdmin)).andExpect(status().isNoContent)
        assertThat(comments.existsById(onArsenal)).isFalse()
        assertThat(comments.existsById(onChelsea)).isFalse()
    }

    @Test
    fun `댓글 목록의 moderatable은 그 기사를 관리할 수 있는 관리자에게만 참`() {
        commentBy(troll, arsenalArticle)
        mvc.perform(get("/api/news/$arsenalArticle/comments").as_(arsenalAdmin)).andExpect(jsonPath("$.content[0].moderatable").value(true))
        mvc.perform(get("/api/news/$arsenalArticle/comments").as_(chelseaAdmin)).andExpect(jsonPath("$.content[0].moderatable").value(false))
        mvc.perform(get("/api/news/$arsenalArticle/comments").as_(fan)).andExpect(jsonPath("$.content[0].moderatable").value(false))
        mvc.perform(get("/api/news/$arsenalArticle/comments")).andExpect(jsonPath("$.content[0].moderatable").value(false))
    }

    @Test
    fun `정지는 사유가 필요하고, 정지되면 댓글을 못 쓰고 내 정보에 정지가 보인다`() {
        val c = commentBy(troll, arsenalArticle, "신고될 댓글")

        mvc.perform(suspendReq(c, reason = " ").as_(arsenalAdmin)).andExpect(status().isBadRequest)
        mvc.perform(suspendReq(c, days = 5).as_(arsenalAdmin)).andExpect(status().isBadRequest)

        mvc.perform(suspendReq(c, reason = "반복 욕설").as_(arsenalAdmin))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.nickname").value(troll.nickname))
            .andExpect(jsonPath("$.club").value("arsenal"))
            .andExpect(jsonPath("$.commentContent").value("신고될 댓글"))
            .andExpect(jsonPath("$.active").value(true))
        assertThat(comments.existsById(c)).isFalse() // 댓글도 지워졌다 (내용은 정지 기록에)

        mvc.perform(post("/api/news/$arsenalArticle/comments").as_(troll).contentType(MediaType.APPLICATION_JSON).content("""{"content":"또 씀"}"""))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value(containsString("반복 욕설")))
        mvc.perform(get("/api/auth/me").as_(troll))
            .andExpect(jsonPath("$.suspensionReason").value("반복 욕설"))
            .andExpect(jsonPath("$.suspendedUntil").exists())
    }

    @Test
    fun `다른 팀 기사 댓글 작성자는 정지할 수 없고, 관리자는 정지할 수 없다`() {
        mvc.perform(suspendReq(commentBy(troll, chelseaArticle)).as_(arsenalAdmin)).andExpect(status().isForbidden)
        mvc.perform(suspendReq(commentBy(chelseaAdmin, arsenalArticle)).as_(arsenalAdmin)).andExpect(status().isForbidden)
    }

    @Test
    fun `정지 목록은 관리자만, 해제는 같은 팀 범위 관리자나 전체 관리자만`() {
        val id = suspensions.findAll().size.let {
            mvc.perform(suspendReq(commentBy(troll, arsenalArticle), reason = "도배$tag").as_(arsenalAdmin)).andExpect(status().isCreated)
            suspensions.findAll().first { it.reason == "도배$tag" }.id!!
        }

        mvc.perform(get("/api/moderation/suspensions").as_(fan)).andExpect(status().isForbidden)
        mvc.perform(get("/api/moderation/suspensions").param("club", "arsenal").as_(chelseaAdmin))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[?(@.id == $id)].liftable").value(false))

        mvc.perform(post("/api/moderation/suspensions/$id/lift").as_(chelseaAdmin)).andExpect(status().isForbidden)
        mvc.perform(post("/api/moderation/suspensions/$id/lift").as_(arsenalAdmin))
            .andExpect(status().isOk).andExpect(jsonPath("$.active").value(false))
            .andExpect(jsonPath("$.liftedByNickname").value(arsenalAdmin.nickname))

        // 해제했으니 다시 쓸 수 있다
        mvc.perform(post("/api/news/$arsenalArticle/comments").as_(troll).contentType(MediaType.APPLICATION_JSON).content("""{"content":"반성합니다"}"""))
            .andExpect(status().isCreated)
        // 지금 정지 중 목록에는 없고, 전체 목록에는 남는다
        mvc.perform(get("/api/moderation/suspensions").param("size", "100").as_(superAdmin))
            .andExpect(jsonPath("$.content[?(@.id == $id)]").isEmpty)
        mvc.perform(get("/api/moderation/suspensions").param("active", "false").param("size", "100").as_(superAdmin))
            .andExpect(jsonPath("$.content[?(@.id == $id)]").isNotEmpty)
    }

    @Test
    fun `정지 중 탈퇴하고 같은 계정으로 다시 가입해도 정지가 이어진다`() {
        mvc.perform(suspendReq(commentBy(troll, arsenalArticle)).as_(superAdmin)).andExpect(status().isCreated)
        memberService.withdraw(troll)

        val again = members.save(Member(provider = troll.provider, providerUserId = troll.providerUserId, nickname = "새이름$tag", favoriteTeam = FavoriteTeam.OTHER, signupCompleted = true))
        mvc.perform(post("/api/news/$arsenalArticle/comments").as_(again).contentType(MediaType.APPLICATION_JSON).content("""{"content":"새 계정"}"""))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `운영자 키로만 권한을 바꾸고, 팀 관리자는 팀이 필요하다`() {
        val path = "/api/admin/members/${fan.id}/role"
        mvc.perform(put(path).as_(superAdmin).contentType(MediaType.APPLICATION_JSON).content("""{"role":"ADMIN"}"""))
            .andExpect(status().isUnauthorized) // 회원 관리자도 권한은 못 바꾼다
        mvc.perform(put(path).header("X-Admin-Key", "test-admin-key").contentType(MediaType.APPLICATION_JSON).content("""{"role":"TEAM_ADMIN"}"""))
            .andExpect(status().isBadRequest)
        mvc.perform(put(path).header("X-Admin-Key", "test-admin-key").contentType(MediaType.APPLICATION_JSON).content("""{"role":"TEAM_ADMIN","club":"liverpool"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.role").value("TEAM_ADMIN")).andExpect(jsonPath("$.adminClub").value("liverpool"))
        mvc.perform(get("/api/auth/me").as_(fan)).andExpect(jsonPath("$.role").value("TEAM_ADMIN"))
        mvc.perform(get("/api/admin/members").param("nickname", "팬$tag").header("X-Admin-Key", "test-admin-key"))
            .andExpect(jsonPath("$[0].id").value(fan.id!!))
    }
}
