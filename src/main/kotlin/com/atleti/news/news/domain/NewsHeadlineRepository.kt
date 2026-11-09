package com.atleti.news.news.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface NewsHeadlineRepository : JpaRepository<NewsHeadline, Long> {
    // 커서(직전 생성 시각) 계산용: 그 팀의 가장 최근 헤드라인
    fun findTopByClubOrderByCreatedAtDesc(club: String): NewsHeadline?

    // 최신순 전체 (중복 방지 참고 + 롤링 5개 유지용)
    fun findAllByClubOrderByCreatedAtDesc(club: String): List<NewsHeadline>

    // 프론트 노출용: 최신 5개
    fun findTop5ByClubOrderByCreatedAtDesc(club: String): List<NewsHeadline>
}
