package com.atleti.news.member.domain

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * 탈퇴 때 카카오 앱 연결 끊기. 우리는 카카오 액세스 토큰을 저장하지 않으므로 **어드민 키**로 끊는다.
 * 어드민 키가 없으면 건너뛴다 — 우리 쪽 회원 정보는 이미 지워졌고, 카카오 연결은 사용자가
 * 카카오 계정 설정에서도 끊을 수 있다.
 *
 * 구글은 끊으려면 사용자 토큰이 필요해 하지 않는다 (사용자가 구글 계정 > 보안 > 서드파티 앱에서 끊는다).
 */
@Component
class KakaoUnlinker(@Value("\${atleti.auth.kakao.admin-key:}") private val adminKey: String) {
    private val log = LoggerFactory.getLogger(KakaoUnlinker::class.java)
    private val webClient = WebClient.builder().build()

    fun unlink(kakaoUserId: String) {
        if (adminKey.isBlank()) return
        try {
            webClient.post().uri("https://kapi.kakao.com/v1/user/unlink")
                .header("Authorization", "KakaoAK ${adminKey.trim()}")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("target_id_type", "user_id").with("target_id", kakaoUserId))
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(10))
        } catch (e: Exception) {
            log.warn("⚠️ [Member] 카카오 연결 끊기 실패 (탈퇴는 완료): {}", e.message)
        }
    }
}
