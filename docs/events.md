# 이벤트 (아웃박스 → Redis Streams)

football-svc(factos-BE)가 밖으로 알리는 일. 첫 소비자는 travel-svc(킥오프 알림·직관 기록)다.

```
일정·결과 저장 ─┬─ 같은 트랜잭션 ─▶ outbox_event (DB)
                │
OutboxRelay (2초마다) ── 아직 안 보낸 것 id 순 ──▶ XADD events:match ──▶ 소비자 그룹 (travel-svc …)
```

## 왜 아웃박스인가

DB 저장과 Redis 발행을 따로 하면 둘 중 하나만 성공하는 순간이 생긴다.

- 일정은 저장됐는데 Redis가 죽어 있으면 → 알림이 영영 안 간다
- Redis에 먼저 올렸는데 DB가 롤백되면 → 일어나지 않은 변경을 알린다

그래서 이벤트를 **데이터와 같은 트랜잭션에서 DB 테이블에 적고**(`OutboxWriter`, `MANDATORY`라 트랜잭션 밖에서는 못 부른다),
`OutboxRelay`가 따로 Redis에 옮긴다. Redis가 죽어 있으면 아웃박스에 쌓였다가 돌아오면 순서대로 나간다.

## 전달 보장 — 소비자가 지킬 것

- **최소 한 번**: XADD 직후 DB에 "보냄" 표시 전에 서버가 죽으면 같은 이벤트가 한 번 더 나간다. **`id`로 중복을 거른다**
- **순서**: 같은 스트림 안에서는 아웃박스 id 순. 다른 스트림끼리는 보장 없음
- **보관**: 스트림은 약 10,000개만 남긴다 (`MAXLEN ~`). 소비자가 그보다 오래 멈춰 있으면 오래된 것을 놓친다
- 소비는 **소비자 그룹**으로: `XGROUP CREATE events:match travel-svc $ MKSTREAM` → `XREADGROUP … >` → 처리 후 `XACK`
- 아웃박스 행은 보낸 뒤 7일 지나면 지운다 (`OutboxRelay.cleanUp`)

## 레코드 모양 (스트림 필드)

| 필드 | 예 | 설명 |
|---|---|---|
| `id` | `42` | 아웃박스 id. 중복 제거 키 |
| `type` | `match.scheduled` | 아래 표 |
| `aggregateId` | `5795522` | FotMob matchId (채팅방 키와 같다) |
| `occurredAt` | `2026-11-09T08:16:14.376Z` | 아웃박스에 적힌 시각 (ISO-8601, 서버 시간대 — 컨테이너는 UTC) |
| `payload` | `{...}` | JSON, 타입별 아래 |

## `events:match`

`tournament`는 한글 대회명이다 — 일정 API(`/api/matches/clubs/{club}/upcoming`)와 같은 값 (`TournamentNameKo`).
일정 이벤트는 **팀 기준**이다. 맞대결(아스날-첼시)은 두 팀 일정에 각각 있어서 `club`만 다른 이벤트가 두 번 나간다.
FotMob은 팀마다 앞으로 3경기만 보여 주므로, 일정 이벤트는 "그 팀의 다가올 3경기 창"의 변화다.

| type | 언제 | payload |
|---|---|---|
| `match.scheduled` | 그 팀의 다가올 경기 창에 처음 들어옴 | Fixture |
| `match.updated` | 킥오프 시각·대회·홈원정이 바뀜 | Fixture (+ 시각이 바뀌었으면 `previousKickoff`) |
| `match.cancelled` | 킥오프 전인데 창 안에서 빠짐 (연기·취소) | Fixture (빠지기 전 값) |
| `match.finished` | 경기 결과가 처음 저장됨 (팀당 아니라 경기당 한 번) | Result |

`scheduled`·`updated`는 **upsert**로 받으면 된다 — 같은 경기가 창을 나갔다 다시 들어오면 `scheduled`가 또 올 수 있다.

```jsonc
// Fixture
{ "matchId": "5795522", "club": "mancity", "opponent": "Fulham",
  "kickoff": "2026-11-21T21:30:00+09:00", "isHome": true, "tournament": "프리미어리그",
  "previousKickoff": null }

// Result
{ "matchId": "5795511", "clubs": ["tottenham"], "homeTeam": "Leeds", "awayTeam": "Tottenham",
  "homeScore": 1, "awayScore": 0, "kickoff": "2026-11-07T21:30:00+09:00", "tournament": "프리미어리그" }
```

취소 판정 (`MatchEvents.diff`): 목록에서 빠진 경기 가운데 **킥오프 전이고, 새 목록의 마지막 경기보다 이른 것**만 취소로 본다.
킥오프가 지나서 빠진 건 경기를 한 것(`finished`가 따로 나감), 더 이른 경기가 끼어들어 창 밖으로 밀린 건 아무 일도 아니다.

## `events:member`

회원은 football-svc에만 있다 (가입·로그인도 여기서만). 다른 서비스는 이 이벤트로 **작성자 표시용 사본**을 맞춘다.

| type | 언제 | payload |
|---|---|---|
| `member.updated` | 로그인·가입 마치기·프로필 수정·정지·정지 해제·권한 변경 | 지금 상태 전체 (덮어쓰면 된다) |
| `member.deleted` | 탈퇴 | `{"memberId": 6}` — 글은 남기고 "탈퇴한 회원"으로 |

```jsonc
{ "memberId": 1, "nickname": "북런던직관러", "profileImageUrl": null, "favoriteTeam": "arsenal",
  "signupCompleted": true, "role": "MEMBER", "adminClub": null,
  "suspendedUntil": null, "suspensionReason": null }
```

필드 이름은 `GET /api/auth/me` 응답과 같다 (`id` 대신 `memberId`). 처음 보는 회원은 그 API로 채우면 된다.

## 공유 로그인 (JWT)

- 로그인 쿠키 `factos_session` = JWT (ES256, `iss=factos`, `sub=회원 id`). 같은 도메인이라 모든 서비스 요청에 실린다
- 공개 키: `GET /.well-known/jwks.json` (내부망 `http://backend:8080`). 다른 서비스는 이걸로 **직접 검증**한다 — 비밀값 공유 없음
- 서명 키는 `AUTH_JWT_KEY` (infra/compose/.env.example에 만드는 명령). 비면 재시작마다 전원 로그아웃
- 예전 HMAC 쿠키(점 1개)는 만료 전까지 football-svc가 받아 준다. 다른 서비스는 그 쿠키로 `/api/auth/me`를 불러 확인한다

## 확인 명령

```bash
docker compose exec redis redis-cli XLEN events:match
docker compose exec redis redis-cli XLEN events:member
docker compose exec redis redis-cli XRANGE events:match - + COUNT 5
docker compose exec postgres psql -U factos factos -c "select id, event_type, aggregate_id, published_at from outbox_event order by id desc limit 10"
```
