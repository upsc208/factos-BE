package com.atleti.news.news.domain

/**
 * Big6 팀별 기자 트위터 계정과, 트윗을 팀에 붙일 때 쓰는 키워드.
 *
 * 명단은 factos 1기 DataInitializer에서 옮겼다 (tier 숫자는 버림).
 *
 * 팀을 붙이는 규칙 ([clubsFor]):
 * - 한 팀에만 있는 기자(팀 전담 기자) → 그 팀. 트윗 내용은 보지 않는다
 * - 여러 팀에 있는 기자(Ornstein, Romano 등) → 트윗에 나온 팀 키워드로 고른다. 어느 팀도 안 나오면 버린다
 */
object ClubJournalists {

    private val BY_CLUB: Map<Club, List<String>> = mapOf(
        Club.ARSENAL to listOf(
            "David_Ornstein", "SamiMokbel81_DM", "gunnerblog", "handofarsnal", "charles_watts",
            "amy_lawrence", "TeamNewsAndTicks", "JamesOlley", "SimonCollings_",
        ),
        Club.MAN_UTD to listOf(
            "sistoney67", "David_Ornstein", "lauriewhitwell", "MikeKeegan_DM", "TelegraphDucker",
            "hirstclass", "mcgrathmike", "samuelluckhurst",
        ),
        Club.MAN_CITY to listOf(
            "Jack_Gaughan", "mcgrathmike", "spbajko", "David_Ornstein", "sistoney67",
            "TelegraphDucker", "SamLee", "SunMartinB",
        ),
        Club.LIVERPOOL to listOf(
            "_pauljoyce", "David_Ornstein", "_ChrisBascombe", "SamiMokbel81_DM", "JamesPearceLFC",
            "LewisSteele_", "IanDoyleSport", "ptgorst", "DaveOCKOP",
        ),
        Club.CHELSEA to listOf(
            "David_Ornstein", "Matt_Law_DT", "SJohnsonSport", "NizaarKinsella", "SamiMokbel81_DM",
            "FabriceHawkins", "KieranGill_DM", "MatteoMoretto",
        ),
        Club.TOTTENHAM to listOf(
            "David_Ornstein", "SkySportsLyall", "GeorgeSessions", "Matt_Law_DT", "SamiMokbel81_DM",
            "pokeefe1", "DaveHytner", "AlasdairGold",
        ),
    ).mapValues { (_, handles) -> handles + "FabrizioRomano" } // 1기와 같이 모든 팀에 추가

    /** 기자 계정 → 그 기자가 다루는 팀들. 중복을 뺀 39명 */
    val HANDLES: Map<String, Set<Club>> = BY_CLUB
        .flatMap { (club, handles) -> handles.map { it to club } }
        .groupBy({ it.first }, { it.second })
        .mapValues { it.value.toSet() }

    /**
     * 팀 키워드. 1기 목록에서 두 가지를 뺐다:
     * - 너무 흔한 단어 (City, United, Son, Rice, Slot, Johnson, Jackson, AFC …) — 다른 팀 얘기에도 걸린다
     * - 1기 당시 이적 루머 대상 (Wirtz, Musiala, Isak …) — 그 팀 선수가 아니라 엉뚱한 팀에 붙는다
     * 감독·선수가 바뀌면 여기를 고친다.
     */
    private val KEYWORDS: Map<Club, List<String>> = mapOf(
        Club.MAN_UTD to listOf(
            "Manchester United", "Man United", "Man Utd", "Manchester Utd", "MUFC", "Old Trafford", "Red Devils",
            "Carrick", "Amorim", "Hojlund", "Zirkzee", "Rashford", "Garnacho", "Amad", "Bruno Fernandes", "Mainoo",
            "Ugarte", "Casemiro", "Dalot", "Mazraoui", "De Ligt", "Lisandro Martinez", "Yoro", "Onana", "Sesko", "Mbeumo",
        ),
        Club.MAN_CITY to listOf(
            "Manchester City", "Man City", "MCFC", "Etihad Stadium", "Cityzens", "Pep Guardiola", "Guardiola",
            "Haaland", "Foden", "De Bruyne", "Rodri", "Bernardo Silva", "Gundogan", "Kovacic", "Grealish", "Doku",
            "Savinho", "Akanji", "Ruben Dias", "Gvardiol", "Ederson",
        ),
        Club.LIVERPOOL to listOf(
            "Liverpool", "LFC", "Anfield", "Arne Slot",
            "Salah", "Luis Diaz", "Darwin Nunez", "Gakpo", "Szoboszlai", "Mac Allister", "Gravenberch", "Curtis Jones",
            "Van Dijk", "Konate", "Robertson", "Alisson", "Kelleher",
        ),
        Club.ARSENAL to listOf(
            "Arsenal", "Emirates Stadium", "Gunners", "Arteta",
            "Havertz", "Saka", "Martinelli", "Odegaard", "Declan Rice", "Merino", "Trossard", "Saliba",
            "Gabriel Magalhaes", "Ben White", "Timber", "Calafiori", "David Raya",
        ),
        Club.CHELSEA to listOf(
            "Chelsea", "CFC", "Stamford Bridge", "Maresca",
            "Cole Palmer", "Nico Jackson", "Madueke", "Enzo Fernandez", "Caicedo", "Lavia", "Cucurella", "Colwill",
            "Fofana", "Reece James", "Gusto", "Robert Sanchez", "Estevao",
        ),
        Club.TOTTENHAM to listOf(
            "Tottenham", "Spurs", "THFC", "Postecoglou",
            "Son Heung-min", "Heung-min Son", "Solanke", "Brennan Johnson", "Kulusevski", "Maddison", "Bentancur",
            "Bissouma", "Udogie", "Van de Ven", "Romero", "Porro", "Vicario", "Bergvall", "Archie Gray",
        ),
    )

    // 단어 단위로만 맞춘다 — "Spurs"가 "spurstalk" 해시태그 안에, "Saka"가 다른 단어 안에 걸리지 않게
    private val PATTERNS: Map<Club, Regex> = KEYWORDS.mapValues { (_, words) ->
        Regex(
            words.joinToString("|", prefix = "(?<![\\p{L}\\p{N}])(?:", postfix = ")(?![\\p{L}\\p{N}])") { Regex.escape(it) },
            RegexOption.IGNORE_CASE,
        )
    }

    /** 이 팀을 다루는 기자 계정 (명단 순서 그대로) */
    fun handlesFor(club: Club): List<String> = BY_CLUB[club].orEmpty()

    /** 이 기자의 이 트윗이 걸릴 팀들. 비어 있으면 저장하지 않는다 */
    fun clubsFor(handle: String, text: String): Set<Club> {
        val covered = HANDLES[handle].orEmpty()
        if (covered.size <= 1) return covered
        return covered.filter { PATTERNS.getValue(it).containsMatchIn(text) }.toSet()
    }
}
