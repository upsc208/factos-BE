package com.atleti.news.match.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "upcoming_match",
    uniqueConstraints = [UniqueConstraint(columnNames = ["club", "opponent", "matchDate"])]
)
class UpcomingMatch(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * 이 일정이 누구의 경기인지 (Club.id). Big6끼리 붙으면 **같은 matchId로 두 행**이 생긴다
     * (아스날 행: 상대 첼시 / 첼시 행: 상대 아스날). 채팅은 matchId로 방을 잡으므로 두 팀 팬이 한 방에 모인다.
     * 아틀레티 시절 행은 null이고, 다음 일정 갱신 때 지워진다.
     */
    val club: String? = null,

    // 외부 API의 경기 id — MatchResult.matchId와 같은 체계. 채팅방 키로 사용 (docs/redis-design.md 6.6)
    val matchId: String? = null,

    @Column(nullable = false)
    val opponent: String,

    @Column(nullable = false)
    val matchDate: String,
    
    val isHome: Boolean,
    val tournament: String,

    val updatedAt: LocalDateTime = LocalDateTime.now()
)
