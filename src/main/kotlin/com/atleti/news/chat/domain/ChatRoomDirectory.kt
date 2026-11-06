package com.atleti.news.chat.domain

import com.atleti.news.chat.infra.ChatPresenceTracker
import com.atleti.news.match.domain.FootballDataService
import com.atleti.news.match.domain.MatchKickoff
import com.atleti.news.match.domain.UpcomingMatch
import com.atleti.news.match.domain.UpcomingMatchRepository
import com.atleti.news.news.domain.Club
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

enum class ChatRoomType { TEAM, MATCH }

/** 팀 페이지에 보여 줄 채팅방 하나 */
data class ChatRoomSummary(
    val roomId: String, // WebSocket 구독·전송과 /api/chat/{roomId}에 그대로 쓴다
    val type: ChatRoomType,
    val title: String,
    val clubs: List<String>, // 이 방에 모이는 팀 (Club.id). Big6 맞대결 매치 방이면 2개
    val open: Boolean,
    val onlineCount: Long,
    val kickoff: OffsetDateTime? = null, // 매치 방만
    val tournament: String? = null,
)

/**
 * 팀별 채팅방 목록: 팀 방 1개 + 그 팀의 다가올 경기 방들.
 *
 * 매치 방의 방 키는 FotMob 경기 ID라서, 아스날-첼시전은 아스날 페이지와 첼시 페이지에서
 * **같은 방**으로 들어간다. 이 목록은 그 사실을 [ChatRoomSummary.clubs]로 알려 줄 뿐, 방을 따로 잇지 않는다.
 */
@Service
class ChatRoomDirectory(
    private val chatService: ChatService,
    private val presenceTracker: ChatPresenceTracker,
    private val footballDataService: FootballDataService,
    private val upcomingMatchRepository: UpcomingMatchRepository,
) {
    fun roomsFor(club: Club): List<ChatRoomSummary> {
        val teamRoomId = ChatService.teamRoomId(club)
        val teamRoom = ChatRoomSummary(
            roomId = teamRoomId,
            type = ChatRoomType.TEAM,
            title = "${club.nameKo} 팬 채팅",
            clubs = listOf(club.id),
            open = true,
            onlineCount = presenceTracker.onlineCount(teamRoomId),
        )

        val matches = footballDataService.getUpcomingMatches(club).filter { it.matchId != null }
        // 이 경기들에 걸린 Big6 팀 전부 (상대도 Big6면 그 팀 일정에도 같은 경기 ID가 있다)
        val clubsByMatch = upcomingMatchRepository.findAllByMatchIdIn(matches.mapNotNull { it.matchId })
            .groupBy({ it.matchId!! }, { it.club })
            .mapValues { (_, ids) -> ids.filterNotNull().distinct() }

        val matchRooms = matches.map { match ->
            val matchId = match.matchId!!
            val clubs = (listOf(club.id) + clubsByMatch[matchId].orEmpty()).distinct()
            ChatRoomSummary(
                roomId = matchId,
                type = ChatRoomType.MATCH,
                title = matchTitle(club, match, clubs),
                clubs = clubs,
                open = chatService.isOpen(matchId),
                onlineCount = presenceTracker.onlineCount(matchId),
                kickoff = MatchKickoff.parse(match.matchDate),
                tournament = match.tournament,
            )
        }
        return listOf(teamRoom) + matchRooms
    }

    companion object {
        /** "아스날 vs 첼시" — 홈팀이 앞. 상대가 Big6면 한국어 이름, 아니면 FotMob 표기 그대로 */
        internal fun matchTitle(club: Club, match: UpcomingMatch, clubs: List<String>): String {
            val opponent = clubs.firstOrNull { it != club.id }?.let { Club.fromId(it)?.nameKo } ?: match.opponent
            return if (match.isHome) "${club.nameKo} vs $opponent" else "$opponent vs ${club.nameKo}"
        }
    }
}
