package com.atleti.news.member

import com.atleti.news.member.auth.MemberAuth
import com.atleti.news.member.auth.MemberResponse
import com.atleti.news.member.domain.MemberService
import com.atleti.news.member.domain.NicknameCheck
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class ProfileRequest(val nickname: String?, val favoriteTeam: String?)

@Tag(name = "Member API", description = "가입 마치기·내 정보·탈퇴")
@RestController
class MemberController(
    private val memberAuth: MemberAuth,
    private val memberService: MemberService,
    private val moderation: com.atleti.news.moderation.ModerationService,
) {
    @Operation(
        summary = "닉네임 중복 확인",
        description = "형식(2~12자, 한글·영문·숫자·_), 금칙어, 중복을 본다. 로그인 상태면 지금 내 닉네임은 사용 가능으로 본다",
    )
    @GetMapping("/api/members/nickname-check")
    fun checkNickname(@RequestParam nickname: String, request: HttpServletRequest): NicknameCheck =
        memberService.check(nickname, memberAuth.current(request))

    @Operation(summary = "가입 마치기 / 내 정보 수정", description = "닉네임·응원팀(arsenal, chelsea, liverpool, mancity, manutd, tottenham, other)")
    @PutMapping("/api/me/profile")
    fun updateProfile(@RequestBody body: ProfileRequest, request: HttpServletRequest): MemberResponse {
        val member = memberAuth.require(request) // 가입 마치기 전에도 부를 수 있어야 한다
        return memberService.updateProfile(member, body.nickname, body.favoriteTeam).let { MemberResponse.of(it, moderation.activeSuspension(it)) }
    }

    @Operation(summary = "회원 탈퇴", description = "회원 정보·즐겨찾기 삭제. 댓글은 '탈퇴한 회원'으로 남는다")
    @DeleteMapping("/api/me")
    fun withdraw(request: HttpServletRequest): ResponseEntity<Void> {
        memberService.withdraw(memberAuth.require(request))
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, memberAuth.clearSessionCookie().toString()).build()
    }
}
