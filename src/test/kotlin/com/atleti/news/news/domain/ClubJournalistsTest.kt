package com.atleti.news.news.domain

import com.atleti.news.news.domain.ClubJournalists.HANDLES
import com.atleti.news.news.domain.ClubJournalists.clubsFor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ClubJournalistsTest {

    @Test
    fun `1기 명단에서 중복을 뺀 39명이고, Romano와 Ornstein은 여섯 팀 모두에 있다`() {
        assertThat(HANDLES).hasSize(39)
        assertThat(HANDLES["FabrizioRomano"]).hasSize(6)
        assertThat(HANDLES["David_Ornstein"]).hasSize(6)
        assertThat(HANDLES["JamesPearceLFC"]).containsExactly(Club.LIVERPOOL)
    }

    @Test
    fun `팀 전담 기자의 트윗은 내용과 상관없이 그 팀에 붙는다`() {
        assertThat(clubsFor("JamesPearceLFC", "Training update from Kirkby this morning"))
            .containsExactly(Club.LIVERPOOL)
    }

    @Test
    fun `여러 팀을 다루는 기자는 트윗에 나온 팀에만 붙는다`() {
        assertThat(clubsFor("FabrizioRomano", "Arsenal agree fee with Chelsea for Madueke. Here we go!"))
            .containsExactlyInAnyOrder(Club.ARSENAL, Club.CHELSEA)
        assertThat(clubsFor("David_Ornstein", "Haaland set to start for Man City"))
            .containsExactly(Club.MAN_CITY)
    }

    @Test
    fun `어느 팀 키워드도 없으면 버린다`() {
        assertThat(clubsFor("FabrizioRomano", "Real Madrid complete deal for new centre-back")).isEmpty()
    }

    @Test
    fun `키워드는 단어 단위로만 맞춘다`() {
        // "Spurs"가 해시태그 단어 안에, "Saka"가 다른 단어 안에 들어 있어도 걸리면 안 된다
        assertThat(clubsFor("FabrizioRomano", "#spurstalk podcast with Sakamoto")).isEmpty()
        assertThat(clubsFor("FabrizioRomano", "SPURS confirm signing")).containsExactly(Club.TOTTENHAM)
    }

    @Test
    fun `명단에 없는 계정은 어느 팀에도 붙지 않는다`() {
        assertThat(clubsFor("unknown_account", "Arsenal news")).isEmpty()
    }
}
