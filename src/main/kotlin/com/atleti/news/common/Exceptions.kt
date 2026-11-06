package com.atleti.news.common

class NotFoundException(message: String) : RuntimeException(message)

class InvalidRequestException(message: String) : RuntimeException(message)

/** 로그인이 필요한 요청 (401) */
class UnauthorizedException(message: String = "로그인이 필요합니다.") : RuntimeException(message)

/** 로그인은 했지만 권한이 없는 요청 — 남의 댓글 삭제 등 (403) */
class ForbiddenException(message: String) : RuntimeException(message)

/** 이미 있는 값과 부딪히는 요청 — 닉네임 중복 등 (409) */
class ConflictException(message: String) : RuntimeException(message)
