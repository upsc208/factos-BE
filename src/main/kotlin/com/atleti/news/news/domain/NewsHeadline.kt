package com.atleti.news.news.domain

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 팀 브리핑 한 줄 (팀 페이지 경기 카드의 "브리핑 5줄"). 팀마다 최신 5줄만 남긴다.
 * [club]이 null인 행은 아틀레티 시절 것이라 읽지 않는다.
 */
@Entity
@Table(name = "news_headline", indexes = [Index(name = "idx_headline_club_created", columnList = "club, created_at")])
class NewsHeadline(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // 여러 최신 뉴스를 종합해 만든 헤드라인 한 줄 (순수 텍스트)
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    val content: String,

    // Club.id ("arsenal" …). 운영 DB는 ddl-auto=update라 nullable
    @Column(name = "club", length = 20)
    val club: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(ZoneId.of("Asia/Seoul"))
)
