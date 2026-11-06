package com.atleti.news.news.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

@Repository
interface NewsArticleRepository : JpaRepository<NewsArticle, Long> {
    fun existsByOriginalUrl(originalUrl: String): Boolean

    fun findAllByOriginalUrlIn(originalUrls: Collection<String>): List<NewsArticle>

    // 번역 대기열: 최신 기사부터 번역해야 지금 보는 사람에게 의미가 있다
    fun findTop20ByTranslationStatusOrderByPublishedAtDesc(status: TranslationStatus): List<NewsArticle>

    /**
     * 팀별 목록. 기사 하나가 여러 팀에 걸려도 이 팀 기준으로는 한 번만 나온다.
     *
     * - sources: 소문자 출처 id 목록. 기사는 authorName이 매체명("bbc sport"), 트윗은 기자 계정이라
     *   authorName 하나로 매체·기자를 같이 거른다. null이면 전체
     * - search: 제목(한·영)과 핵심 문장(한·영)에서 찾는다. 번역 전 기사도 영어로 찾을 수 있게
     */
    @Query(
        value = """
            SELECT n FROM NewsArticle n JOIN n.clubs c
            WHERE c = :club
              AND (:sources IS NULL OR LOWER(CAST(n.authorName AS string)) IN :sources)
              AND (:search IS NULL
                   OR LOWER(CAST(n.titleKo AS string)) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(CAST(n.titleEn AS string)) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(CAST(n.summaryKo AS string)) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(CAST(n.keySentencesEn AS string)) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
        """,
        countQuery = """
            SELECT COUNT(n) FROM NewsArticle n JOIN n.clubs c
            WHERE c = :club
              AND (:sources IS NULL OR LOWER(CAST(n.authorName AS string)) IN :sources)
              AND (:search IS NULL
                   OR LOWER(CAST(n.titleKo AS string)) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(CAST(n.titleEn AS string)) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(CAST(n.summaryKo AS string)) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(CAST(n.keySentencesEn AS string)) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
        """,
    )
    fun findAllByClub(
        @Param("club") club: Club,
        @Param("sources") sources: List<String>?,
        @Param("search") search: String?,
        pageable: Pageable,
    ): Page<NewsArticle>

    @Modifying
    @Transactional
    @Query("UPDATE NewsArticle n SET n.viewCount = n.viewCount + 1 WHERE n.id = :id")
    fun increaseViewCount(@Param("id") id: Long): Int

    @Query("""
        SELECT n FROM NewsArticle n 
        WHERE (:sources IS NULL OR LOWER(CAST(n.sourceName AS string)) IN :sources)
          AND (:category IS NULL OR n.category = :category)
          AND (:search IS NULL OR LOWER(CAST(n.titleKo AS string)) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) 
                               OR LOWER(CAST(n.contentKo AS string)) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
    """)
    fun searchNews(
        @Param("sources") sources: List<String>?,
        @Param("category") category: String?,
        @Param("search") search: String?,
        pageable: Pageable
    ): Page<NewsArticle>

    fun findAllBySourceNameIn(sourceNames: List<String>, pageable: Pageable): Page<NewsArticle>
    fun findAllByCategory(category: String, pageable: Pageable): Page<NewsArticle>
    fun findAllByCategoryAndSourceNameIn(category: String, sourceNames: List<String>, pageable: Pageable): Page<NewsArticle>

    @Query("""
        SELECT n FROM NewsArticle n 
        WHERE n.viewCount >= :minViewCount 
          AND n.publishedAt >= :since
          AND (:sources IS NULL OR LOWER(CAST(n.sourceName AS string)) IN :sources)
          AND (:category IS NULL OR n.category = :category)
    """)
    fun searchHypedNews(
        @Param("minViewCount") minViewCount: Long,
        @Param("since") since: java.time.OffsetDateTime,
        @Param("sources") sources: List<String>?,
        @Param("category") category: String?,
        pageable: Pageable
    ): Page<NewsArticle>

    // 헤드라인 브리핑용: 커서(직전 생성 시각) 이후 수집된 ATM 관련 기사만 최신순으로
    @Query("""
        SELECT n FROM NewsArticle n
        WHERE n.createdAt >= :since
          AND n.isAtmRelated = true
        ORDER BY n.createdAt DESC
    """)
    fun findRecentForBriefing(
        @Param("since") since: java.time.OffsetDateTime,
        pageable: Pageable
    ): List<NewsArticle>
}
