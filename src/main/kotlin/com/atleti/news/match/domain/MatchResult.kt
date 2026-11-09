package com.atleti.news.match.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "match_result", uniqueConstraints = [UniqueConstraint(columnNames = ["matchId"])])
class MatchResult(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(unique = true, nullable = false)
    val matchId: String,
    
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int,
    val matchDate: String,
    val tournament: String,
    val isHome: Boolean,
    
    @Column(columnDefinition = "TEXT")
    val scorers: String? = null,
    
    // 추가된 핵심 통계 지표들
    @Column(columnDefinition = "TEXT")
    val lineup: String? = null, // 선발 명단 포맷팅 텍스트
    @Column(columnDefinition = "TEXT")
    val substitutions: String? = null, // 교체 정보 포맷팅 텍스트
    @Column(columnDefinition = "TEXT")
    val cards: String? = null, // 경고 및 퇴장 정보
    val homeFormation: String? = null, // 홈팀 포메이션 (예: "4-4-2")
    val awayFormation: String? = null, // 원정팀 포메이션 (예: "4-3-3")
    val possession: String? = null, // 예: "45 - 55"
    val shotsOnTarget: String? = null, // 예: "5 - 3"
    val xg: String? = null, // 예전 통계 JSON 자리. VARCHAR(255)라 통계를 늘리면 넘친다 → 새 행은 [stats]에

    // 통계 JSON (Map<표시 키, StatValue>). 운영 DB는 ddl-auto=update라 기존 xg 컬럼을 넓힐 수 없어 새 TEXT 컬럼을 둔다
    @Column(columnDefinition = "TEXT")
    val stats: String? = null,

    // Big6 팀별 조회용 FotMob 팀 ID. Big6 맞대결은 행 하나를 두 팀이 같이 쓰므로 홈/원정은 이 값으로 정한다
    // (운영 DB는 ddl-auto=update라 nullable)
    val homeTeamId: String? = null,
    val awayTeamId: String? = null,

    // FotMob 선수 평점 JSON (List<FotmobPlayerRating>). 양 팀 모두
    @Column(columnDefinition = "TEXT")
    val playerRatings: String? = null,
    val playerOfTheMatch: String? = null,

    val updatedAt: LocalDateTime = LocalDateTime.now()
)
