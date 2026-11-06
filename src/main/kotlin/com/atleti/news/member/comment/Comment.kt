package com.atleti.news.member.comment

import jakarta.persistence.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * 기사 댓글. 기사·회원과 JPA 연관관계를 걸지 않고 id만 들고 있다 —
 * 목록은 회원 id를 모아 한 번에 읽고, 기사가 삭제돼도 댓글 저장에 영향이 없게 하려는 것.
 */
@Entity
@Table(
    name = "article_comment",
    indexes = [Index(name = "idx_comment_article_created", columnList = "article_id, created_at")],
)
class Comment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "article_id", nullable = false)
    val articleId: Long,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(nullable = false, columnDefinition = "TEXT")
    val content: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface CommentRepository : JpaRepository<Comment, Long> {
    fun findAllByArticleId(articleId: Long, pageable: Pageable): Page<Comment>
    fun findFirstByMemberIdOrderByCreatedAtDesc(memberId: Long): Comment?
    fun countByArticleId(articleId: Long): Long
}
