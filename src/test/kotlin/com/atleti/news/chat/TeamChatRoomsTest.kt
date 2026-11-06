package com.atleti.news.chat

import com.atleti.news.chat.domain.ChatService
import com.atleti.news.match.domain.UpcomingMatch
import com.atleti.news.match.domain.UpcomingMatchRepository
import com.atleti.news.news.domain.Club
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 팀 채팅방 6개 + 팀별 매치 방. Big6 맞대결은 양 팀 목록에 같은 방으로 나와야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class TeamChatRoomsTest {

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var upcoming: UpcomingMatchRepository

    private val kickoff = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(2)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private val laterKickoff = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(5)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    @AfterEach
    fun cleanUp() = upcoming.deleteAll()

    @Test
    fun `팀 방 키는 team-{팀}이고, 없는 팀이나 경기 ID는 팀 방이 아니다`() {
        assertThat(ChatService.teamRoomId(Club.MAN_CITY)).isEqualTo("team-mancity")
        assertThat(ChatService.teamOf("team-tottenham")).isEqualTo(Club.TOTTENHAM)
        assertThat(ChatService.isTeamRoom("team-barcelona")).isFalse()
        assertThat(ChatService.isTeamRoom("4813523")).isFalse()
        assertThat(ChatService.isTeamRoom("free")).isFalse()
    }

    @Test
    fun `팀 방은 항상 열려 있다`() {
        Club.entries.forEach { club ->
            mvc.perform(get("/api/chat/${ChatService.teamRoomId(club)}")).andExpect(jsonPath("$.open").value(true))
        }
    }

    @Test
    fun `Big6 맞대결은 두 팀 목록에 같은 방 ID로, 두 팀이 함께 걸린 방으로 나온다`() {
        // FotMob은 아스날 일정과 첼시 일정에 같은 경기 ID를 준다
        upcoming.save(UpcomingMatch(club = "arsenal", matchId = "900001", opponent = "Chelsea", matchDate = kickoff, isHome = true, tournament = "Premier League"))
        upcoming.save(UpcomingMatch(club = "chelsea", matchId = "900001", opponent = "Arsenal", matchDate = kickoff, isHome = false, tournament = "Premier League"))
        // 아스날의 다른 경기 — 상대가 Big6가 아니라 아스날 팬만 모인다
        upcoming.save(UpcomingMatch(club = "arsenal", matchId = "900002", opponent = "Brentford", matchDate = laterKickoff, isHome = false, tournament = "Premier League"))

        mvc.perform(get("/api/chat/rooms").param("club", "arsenal"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].roomId").value("team-arsenal"))
            .andExpect(jsonPath("$[0].type").value("TEAM"))
            .andExpect(jsonPath("$[1].roomId").value("900001"))
            .andExpect(jsonPath("$[1].title").value("아스날 vs 첼시"))
            .andExpect(jsonPath("$[1].clubs.length()").value(2))
            .andExpect(jsonPath("$[2].title").value("Brentford vs 아스날"))
            .andExpect(jsonPath("$[2].clubs.length()").value(1))

        mvc.perform(get("/api/chat/rooms").param("club", "chelsea"))
            .andExpect(jsonPath("$[0].roomId").value("team-chelsea"))
            .andExpect(jsonPath("$[1].roomId").value("900001")) // 아스날 페이지와 같은 방
            .andExpect(jsonPath("$[1].title").value("아스날 vs 첼시"))
    }

    @Test
    fun `맞대결 경기는 행이 두 개여도 방 오픈 판정이 깨지지 않는다`() {
        upcoming.save(UpcomingMatch(club = "liverpool", matchId = "900003", opponent = "Manchester United", matchDate = kickoff, isHome = true, tournament = "Premier League"))
        upcoming.save(UpcomingMatch(club = "manutd", matchId = "900003", opponent = "Liverpool", matchDate = kickoff, isHome = false, tournament = "Premier League"))

        // 킥오프 이틀 전 — 아직 닫혀 있다 (예전 findByMatchId는 행이 둘이면 예외로 500)
        mvc.perform(get("/api/chat/900003")).andExpect(status().isOk).andExpect(jsonPath("$.open").value(false))
    }

    @Test
    fun `없는 팀이면 404`() {
        mvc.perform(get("/api/chat/rooms").param("club", "barcelona")).andExpect(status().isNotFound)
    }
}
