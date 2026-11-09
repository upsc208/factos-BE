# factos Docker Compose 스택

설계도의 "Oracle Cloud VM 한 대 · Docker Compose" 부분. 서비스 8개를 한 compose로 띄운다.

```
Cloudflare ──https──▶ nginx:80 ─┬─ /api, /ws ─▶ backend:8080 (Spring) ─┬─ postgres:5432 (pgvector)
                                └─ 그 밖     ─▶ web:3000 (Next.js)      ├─ redis:6379
                                     └─ SSR은 내부망으로 backend:8080    └─ crawler:8000 (FastAPI)
                                /api/travel ─▶ travel:8090 (travel-svc) ─── travel-db:5432 (PostGIS, 자기 DB)
                                                 ├─ REST ─▶ backend:8080
                                                 └─ Redis Stream events:match 소비
```

- **MSA**: football-svc(backend)와 travel-svc는 DB를 같이 쓰지 않는다. 서로는 공개 REST API와 Redis Stream(`docs/events.md`)으로만 통신한다

- 밖으로 열리는 포트는 **nginx 하나**뿐이다. DB·Redis·크롤러·백엔드는 compose 네트워크 안에서 이름으로만 부른다.
- 프론트와 API가 **같은 주소**다 (`https://도메인/api/...`). 그래서 CORS·쿠키 도메인 설정이 필요 없고, 로그인 콜백도 같은 주소다.
- 진짜 클라이언트 IP는 Cloudflare에서 온 요청일 때만 `CF-Connecting-IP`를 믿는다 (`nginx/cloudflare-ips.conf`).
  그래서 **OCI 보안 목록에서 80번은 Cloudflare IP만 열어 둔다** — 그래야 헤더 위조도, Cloudflare 우회도 막힌다.

## 로컬에서 띄우기

세 저장소가 한 폴더에 나란히 있어야 한다 (`factos-BE`, `factos-FE`, `factos-CRAWLER`).

```bash
cd infra/compose
cp .env.example .env   # SITE_URL=http://localhost:8088, NGINX_PORT=8088, AUTH_COOKIE_SECURE=false
# 운영 compose는 레지스트리 이미지만 가리킨다. 소스로 빌드하려면 build 파일을 겹친다
# (tags.env를 같이 주지 않는다 — 주면 빌드한 이미지에 그 태그가 붙어 latest가 옛것으로 남는다)
docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build
open http://localhost:8088
```

## CI/CD (네 저장소 공통)

```
push ─▶ 테스트 ─┐
        gitleaks ┴▶ arm64 이미지 빌드 ─▶ Trivy ─▶ GHCR push ─▶ ssh: deploy.sh <서비스> <커밋 sha>
```

- **보안 게이트**: gitleaks(커밋 기록의 비밀값), Trivy(이미지의 *고칠 수 있는* HIGH/CRITICAL). 하나라도 걸리면 이미지를 올리지 않고 배포도 안 한다.
  해당 없음을 확인한 예외만 `.trivyignore`에 사유·만료일과 함께 적는다 (지금은 백엔드의 Spring 6.2 3건, 2027-03-01 만료)
- 이미지: `ghcr.io/upsc208/factos-{backend,web,crawler,travel}:<sha>` + `latest`. arm64 러너(`ubuntu-24.04-arm`)에서 바로 빌드
- **deploy.sh**: tags.env에 태그 기록 → pull → up → 헬스체크 → 실패하면 이전 태그로 되돌리고 CI를 ❌로
- compose·nginx 설정과 deploy.sh는 **factos-BE가 주인**이다. 백엔드 배포 때 `/opt/factos/compose`로 함께 보낸다

### 저장소마다 넣을 값 (Settings → Secrets and variables → Actions)

| 이름 | 종류 | 저장소 |
|---|---|---|
| `OCI_HOST`, `OCI_USER`, `OCI_SSH_KEY` | Secret | 네 저장소 모두 (factos-BE에는 이미 있음) |
| `SITE_URL` | Variable | factos-FE (빌드 때 박히는 사이트 주소) |

GHCR 패키지는 처음 올라가면 비공개다. 서버가 받으려면 패키지 4개를 **Public**으로 바꾸거나
(GitHub → Packages → 패키지 설정), 서버에서 `read:packages` 토큰으로 `docker login ghcr.io`를 한 번 한다.

## 서버 이전 (systemd jar → compose)

지금 운영은 jar를 systemd로 돌리고 Postgres·Redis를 apt로 깐 구조다. 한 번에 옮긴다.
서버에는 저장소를 clone하지 않는다 — 설정은 CI가 보내고 이미지는 GHCR에서 받는다.

1. Docker 설치 (`docker compose` v2.20 이상 — `--env-file`을 두 번 쓴다)
2. **DB 백업**: `pg_dump -U <user> -d <db> -Fc -f factos.dump`
3. `/opt/factos/compose`, `/opt/factos/models`(번역 모델 4GB) 만들기
4. `/opt/factos/compose/.env` 채우기 — `.env.example` 형식, 값은 기존 `/opt/atleti-news/.env`에서. `CRAWLER_MODELS_DIR=/opt/factos/models`
5. 기존 것 끄기: `sudo systemctl disable --now atleti-news`, 기존 크롤러 compose `down`, 80번을 쓰는 nginx가 있으면 끄기.
   apt Postgres·Redis는 확인이 끝날 때까지 꺼 두기만 한다 (`systemctl disable --now postgresql redis-server`)
6. DB를 옮긴다 (compose 파일은 저장소의 infra/compose에서 한 번 직접 복사해 둔다):
   ```bash
   cd /opt/factos/compose && touch tags.env
   docker compose --env-file .env --env-file tags.env up -d postgres redis
   docker compose --env-file .env --env-file tags.env exec -T postgres pg_restore -U factos -d factos --no-owner < factos.dump
   ```
7. 위 표의 Secret·Variable을 넣고 **factos-BE → factos-CRAWLER → factos-TRAVEL → factos-FE 순서로** 한 번씩 배포가 돌게 한다
   (실패하면 서버에서 `./deploy.sh <서비스> latest`로 다시)
8. Cloudflare DNS를 이 서버로 (프록시 켬). SSL 모드는 처음엔 Flexible(Cloudflare→nginx는 http),
   나중에 Origin 인증서를 nginx 443에 붙이고 Full (strict)로 올린다
9. OCI 보안 목록: 80번은 Cloudflare IP만, 22번은 필요한 곳만
10. 카카오·구글 콘솔의 Redirect URI를 `https://도메인/api/auth/{kakao|google}/callback`으로

## 자주 쓰는 명령

```bash
# 서버 (/opt/factos/compose)
alias dc='docker compose --env-file .env --env-file tags.env'
dc ps                                  # 상태·헬스체크
dc logs -f backend                     # 로그
./deploy.sh backend <sha>              # 특정 버전으로 (되돌리기도 같은 명령)
./deploy.sh config                     # nginx·compose 설정만 다시 적용
dc exec postgres psql -U factos factos
```
