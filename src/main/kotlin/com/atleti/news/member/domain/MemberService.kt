package com.atleti.news.member.domain

import com.atleti.news.chat.domain.SlangFilterService
import com.atleti.news.common.ConflictException
import com.atleti.news.common.InvalidRequestException
import com.atleti.news.member.bookmark.BookmarkRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class NicknameCheck(val nickname: String, val available: Boolean, val reason: String? = null)

@Service
class MemberService(
    private val memberRepository: MemberRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val slangFilter: SlangFilterService,
    private val kakaoUnlinker: KakaoUnlinker,
) {
    private val log = LoggerFactory.getLogger(MemberService::class.java)

    /**
     * 닉네임을 쓸 수 있는지. 형식·금칙어·중복을 이 순서로 본다.
     * [self]는 본인 — 지금 쓰는 닉네임을 그대로 다시 넣는 건 중복이 아니다.
     */
    fun check(raw: String?, self: Member? = null): NicknameCheck {
        val nickname = raw?.trim().orEmpty()
        formatError(nickname)?.let { return NicknameCheck(nickname, false, it) }
        if (slangFilter.containsProfanity(nickname)) return NicknameCheck(nickname, false, "쓸 수 없는 단어가 들어 있습니다.")

        val owner = memberRepository.findByNicknameKey(Member.keyOf(nickname))
        if (owner != null && owner.id != self?.id) return NicknameCheck(nickname, false, "이미 쓰고 있는 닉네임입니다.")
        return NicknameCheck(nickname, true)
    }

    /** 가입 마치기 + 이후 내 정보 수정. 둘 다 같은 규칙이다 */
    @Transactional
    fun updateProfile(member: Member, nickname: String?, teamId: String?): Member {
        val result = check(nickname, member)
        if (!result.available) {
            // 중복은 409 — 프론트가 "다른 닉네임" 안내를 따로 띄울 수 있게
            if (result.reason == DUPLICATE) throw ConflictException(result.reason) else throw InvalidRequestException(result.reason!!)
        }
        val team = FavoriteTeam.fromId(teamId) ?: throw InvalidRequestException("응원하는 팀을 골라 주세요.")

        member.nickname = result.nickname
        member.favoriteTeam = team
        member.signupCompleted = true
        return try {
            memberRepository.saveAndFlush(member)
        } catch (e: DataIntegrityViolationException) {
            // 확인과 저장 사이에 다른 사람이 같은 닉네임을 가져간 경우 — 유니크 제약이 막았다
            throw ConflictException(DUPLICATE)
        }
    }

    /**
     * 탈퇴: 회원 정보와 즐겨찾기를 지운다. 댓글은 남기고 작성자를 "탈퇴한 회원"으로 보여 준다
     * (댓글 목록은 작성자를 못 찾으면 그렇게 표시한다). 같은 계정으로 다시 로그인하면 새 회원으로 가입된다.
     */
    @Transactional
    fun withdraw(member: Member) {
        bookmarkRepository.deleteAllByMemberId(member.id!!)
        memberRepository.delete(member)
        // 카카오 쪽 연결도 끊어 둔다 (다음 로그인 때 동의 화면이 다시 뜬다). 실패해도 탈퇴는 진행
        if (member.provider == AuthProvider.KAKAO) kakaoUnlinker.unlink(member.providerUserId)
        log.info("👋 [Member] 탈퇴 id={}", member.id)
    }

    /** 첫 로그인 때 붙이는 임시 닉네임. 가입 마치기 전까지만 쓴다 */
    fun provisionalNickname(): String {
        repeat(10) {
            val candidate = "팬" + (1..6).map { ALPHANUM.random() }.joinToString("")
            if (!memberRepository.existsByNicknameKey(Member.keyOf(candidate))) return candidate
        }
        error("임시 닉네임을 만들지 못했습니다")
    }

    companion object {
        const val MIN_LENGTH = 2
        const val MAX_LENGTH = 12
        const val DUPLICATE = "이미 쓰고 있는 닉네임입니다."
        private val ALPHANUM = ('a'..'z') + ('0'..'9')
        // 한글·영문·숫자·밑줄만. 공백·특수문자는 사칭("관리자 ") 과 표시 깨짐을 막으려고 막는다
        private val ALLOWED = Regex("^[가-힣a-zA-Z0-9_]+$")
        // 운영자·시스템 표시와 헷갈리는 이름. 소문자로 비교하며, 포함만 해도 막는다
        private val RESERVED = listOf("관리자", "운영자", "admin", "factos", "탈퇴한회원", "운영팀")

        internal fun formatError(nickname: String): String? = when {
            nickname.length < MIN_LENGTH || nickname.length > MAX_LENGTH -> "닉네임은 ${MIN_LENGTH}~${MAX_LENGTH}자로 정해 주세요."
            // 완성형 한글만 허용하므로 "ㅋㅋ" 같은 자음·모음도 여기서 걸린다
            !ALLOWED.matches(nickname) -> "한글·영문·숫자·밑줄(_)만 쓸 수 있습니다."
            RESERVED.any { nickname.lowercase().contains(it) } -> "쓸 수 없는 닉네임입니다."
            else -> null
        }
    }
}
