package com.atleti.news.news.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

enum class ArticleType { WEB_ARTICLE, SNS_POST, YOUTUBE }

/**
 * Big6 기사(RSS 경로)의 번역 진행 상태.
 *
 * 수집과 번역을 나눠 저장하는 이유: 번역이 기사당 1분 걸린다. 번역까지 끝나야 저장하면
 * 새 기사가 그만큼 늦게 보이므로, 영어 제목으로 먼저 보여 주고 번역이 끝나면 바꾼다.
 *
 * 마르카·AS·트위터로 들어온 기존 기사는 처음부터 한국어라 이 값이 null이다.
 */
enum class TranslationStatus { PENDING, DONE }

@Entity
@Table(
    name = "news_article",
    indexes = [Index(name = "idx_news_article_original_url", columnList = "original_url", unique = true)]
)
class NewsArticle(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "article_type", nullable = false)
    val articleType: ArticleType = ArticleType.WEB_ARTICLE,

    @Column(name = "source_name", nullable = false, columnDefinition = "TEXT")
    val sourceName: String,

    @Column(name = "author_name", columnDefinition = "TEXT")
    val authorName: String? = null, // 트위터 핸들 또는 기사 작성자

    @Column(name = "original_url", nullable = false, unique = true, columnDefinition = "TEXT")
    val originalUrl: String,

    @Column(name = "title_ko", columnDefinition = "TEXT")
    var titleKo: String?, // 번역 전(PENDING)에는 null

    @Column(name = "summary_ko", columnDefinition = "TEXT")
    var summaryKo: String?, // Big6 기사는 핵심 문장 번역을 줄바꿈으로 이어 붙인 값

    @Column(name = "content_ko", columnDefinition = "TEXT")
    val contentKo: String?, // 번역 전문

    @Column(name = "content_raw", columnDefinition = "TEXT")
    val contentRaw: String?, // 원문 보존용

    @Column(name = "image_url", columnDefinition = "TEXT")
    val imageUrl: String?,

    @Column(name = "published_at")
    val publishedAt: OffsetDateTime?,

    @Column(name = "category")
    val category: String? = "ETC", // [이적시장], [경기리뷰], [오피셜], [부상/명단], [인터뷰], [예상라인업], [기타]

    @Column(name = "is_atm_related", nullable = false)
    val isAtmRelated: Boolean = false,

    @Column(name = "view_count", nullable = false)
    var viewCount: Long = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    // ---- Big6(RSS) 경로 전용. 기존 기사는 전부 비어 있다 ----

    /**
     * 이 기사가 걸린 팀들. 같은 기사가 여러 팀 피드에 나오면(예: 아스널-첼시 경기 리뷰)
     * 기사는 하나만 저장하고 팀만 늘린다 — 번역을 두 번 하지 않기 위해서다.
     * 팀은 최대 6개라 즉시 로딩해도 부담이 없고, 트랜잭션 밖에서 팀을 더할 때 지연 로딩 오류를 피한다.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "news_article_club", joinColumns = [JoinColumn(name = "article_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "club", nullable = false)
    val clubs: MutableSet<Club> = mutableSetOf(),

    @Column(name = "title_en", columnDefinition = "TEXT")
    val titleEn: String? = null,

    @Column(name = "key_sentences_en", columnDefinition = "TEXT")
    val keySentencesEn: String? = null, // 크롤러가 고른 핵심 문장, 줄바꿈으로 구분

    // DB에서는 null 허용 — 기존 행이 있는 테이블에 NOT NULL 컬럼을 더하면 ddl-auto=update가 실패한다
    @Enumerated(EnumType.STRING)
    @Column(name = "translation_status")
    var translationStatus: TranslationStatus? = null,
)
