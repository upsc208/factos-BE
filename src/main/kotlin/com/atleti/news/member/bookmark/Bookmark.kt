package com.atleti.news.member.bookmark

import com.atleti.news.news.domain.NewsArticle
import jakarta.persistence.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/** 기사 즐겨찾기. 회원·기사 한 쌍에 하나 */
@Entity
@Table(
    name = "bookmark",
    uniqueConstraints = [UniqueConstraint(name = "uk_bookmark_member_article", columnNames = ["member_id", "article_id"])],
)
class Bookmark(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "article_id", nullable = false)
    val articleId: Long,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface BookmarkRepository : JpaRepository<Bookmark, Long> {
    fun existsByMemberIdAndArticleId(memberId: Long, articleId: Long): Boolean

    @Modifying
    @Transactional
    @Query("DELETE FROM Bookmark b WHERE b.memberId = :memberId")
    fun deleteAllByMemberId(@Param("memberId") memberId: Long): Int

    @Modifying
    @Transactional
    @Query("DELETE FROM Bookmark b WHERE b.memberId = :memberId AND b.articleId = :articleId")
    fun deleteByMemberIdAndArticleId(@Param("memberId") memberId: Long, @Param("articleId") articleId: Long): Int

    /** 즐겨찾기한 기사, 최근에 담은 순. 지워진 기사는 JOIN에서 자연히 빠진다 */
    @Query(
        value = """
            SELECT n FROM Bookmark b, NewsArticle n
            WHERE b.articleId = n.id AND b.memberId = :memberId
            ORDER BY b.createdAt DESC
        """,
        countQuery = "SELECT COUNT(b) FROM Bookmark b, NewsArticle n WHERE b.articleId = n.id AND b.memberId = :memberId",
    )
    fun findArticles(@Param("memberId") memberId: Long, pageable: Pageable): Page<NewsArticle>
}
