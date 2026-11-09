package com.atleti.news.news.domain

/**
 * factos가 다루는 EPL Big6.
 *
 * [id]는 프론트 라우트(`/arsenal`, `/mancity` …)와 같은 값이다 — 프론트 `config/clubs`의 키와 맞춘다.
 * 피드 주소에 들어가는 값은 매체마다 달라서 따로 둔다:
 * - BBC는 팀 이름 슬러그 (`feeds.bbci.co.uk/sport/football/teams/{bbcSlug}/rss.xml`)
 * - Sky는 숫자 ID (`skysports.com/rss/{skyFeedId}`). 2026-11-05 피드 제목으로 하나씩 확인했다
 *
 * [fotmobTeamId]는 경기 일정(채팅 매치 방)용. factos 1기 DataInitializer의 값이다.
 * FotMob 팀 페이지 주소의 마지막 조각은 아무 값이어도 ID로 찾아가지만, 읽기 좋게 bbcSlug를 쓴다.
 */
enum class Club(
    val id: String, val bbcSlug: String, val skyFeedId: Int, val fotmobTeamId: String, val nameKo: String,
    val englishName: String, // 브리핑 프롬프트용
) {
    ARSENAL("arsenal", "arsenal", 11670, "9825", "아스날", "Arsenal"),
    CHELSEA("chelsea", "chelsea", 11668, "8455", "첼시", "Chelsea"),
    LIVERPOOL("liverpool", "liverpool", 11669, "8650", "리버풀", "Liverpool"),
    MAN_CITY("mancity", "manchester-city", 11679, "8456", "맨시티", "Manchester City"),
    MAN_UTD("manutd", "manchester-united", 11667, "10260", "맨유", "Manchester United"),
    TOTTENHAM("tottenham", "tottenham-hotspur", 11675, "8586", "토트넘", "Tottenham Hotspur");

    companion object {
        fun fromId(id: String): Club? = entries.find { it.id == id }
        fun fromFotmobId(teamId: String): Club? = entries.find { it.fotmobTeamId == teamId }
    }
}
