# factos Docker Compose 스택

설계도의 "Oracle Cloud VM 한 대 · Docker Compose" 부분. 서비스 6개를 한 compose로 띄운다.

```
Cloudflare ──https──▶ nginx:80 ─┬─ /api, /ws ─▶ backend:8080 (Spring) ─┬─ postgres:5432 (pgvector)
                                └─ 그 밖     ─▶ web:3000 (Next.js)      ├─ redis:6379
                                     └─ SSR은 내부망으로 backend:8080    └─ crawler:8000 (FastAPI)
```

- 밖으로 열리는 포트는 **nginx 하나**뿐이다. DB·Redis·크롤러·백엔드는 compose 네트워크 안에서 이름으로만 부른다.
- 프론트와 API가 **같은 주소**다 (`https://도메인/api/...`). 그래서 CORS·쿠키 도메인 설정이 필요 없고, 로그인 콜백도 같은 주소다.
- 진짜 클라이언트 IP는 Cloudflare에서 온 요청일 때만 `CF-Connecting-IP`를 믿는다 (`nginx/cloudflare-ips.conf`).
  그래서 **OCI 보안 목록에서 80번은 Cloudflare IP만 열어 둔다** — 그래야 헤더 위조도, Cloudflare 우회도 막힌다.

## 로컬에서 띄우기

세 저장소가 한 폴더에 나란히 있어야 한다 (`factos-BE`, `factos-FE`, `factos-CRAWLER`).

```bash
cd infra/compose
cp .env.example .env   # SITE_URL=http://localhost:8088, NGINX_PORT=8088, AUTH_COOKIE_SECURE=false
docker compose up -d --build
open http://localhost:8088
```

## 서버 이전 (systemd jar → compose)

지금 운영은 jar를 systemd로 돌리고 Postgres·Redis를 apt로 깐 구조다. 한 번에 옮긴다.

1. Docker 설치 (`docker compose` 플러그인 포함)
2. **DB 백업**: `pg_dump -U <user> -d <db> -Fc -f factos.dump`
3. 세 저장소를 `/opt/factos/` 아래 나란히 clone, 번역 모델을 `factos-CRAWLER/models`에 복사 (또는 `CRAWLER_MODELS_DIR`)
4. `infra/compose/.env` 채우기 — 기존 `/opt/atleti-news/.env`의 값을 옮긴다
5. DB만 먼저 띄우고 복원:
   ```bash
   docker compose up -d postgres redis
   docker compose exec -T postgres pg_restore -U factos -d factos --no-owner < factos.dump
   ```
6. 기존 것 끄기: `sudo systemctl disable --now atleti-news`, 기존 크롤러 compose `down`, 80번을 쓰는 nginx가 있으면 끄기.
   apt Postgres·Redis는 확인이 끝날 때까지 꺼 두기만 한다 (`systemctl disable --now postgresql redis-server`)
7. `docker compose up -d --build` → `docker compose ps`가 전부 healthy인지 확인
8. Cloudflare DNS를 이 서버로 (프록시 켬). SSL 모드는 처음엔 Flexible(Cloudflare→nginx는 http),
   나중에 Origin 인증서를 nginx 443에 붙이고 Full (strict)로 올린다
9. 카카오·구글 콘솔의 Redirect URI를 `https://도메인/api/auth/{kakao|google}/callback`으로

**주의 — CI 자동 배포**: 지금 `.github/workflows/ci-cd.yml`은 master push 때 jar를 systemd 서비스에 넣는다.
compose로 옮긴 뒤에는 그 배포 단계가 꺼진 서비스를 다시 살리려 든다. 2단계(이미지 빌드·보안 게이트·compose 배포)로
바꾸기 전까지는 master에 push하지 않거나 배포 단계를 끈다.

## 자주 쓰는 명령

```bash
docker compose ps                      # 상태·헬스체크
docker compose logs -f backend         # 로그
docker compose up -d --build backend   # 백엔드만 새로 빌드해 교체
docker compose exec postgres psql -U factos factos
```
