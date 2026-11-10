#!/usr/bin/env bash
# compose 배포 — CI가 이미지를 GHCR에 올린 뒤 ssh로 부른다 (비상시 수동 실행도 같다)
#
#   ./deploy.sh <서비스> <이미지 태그>      예: ./deploy.sh backend 3f2a9c1
#   ./deploy.sh config                       compose·nginx 설정만 다시 적용 (태그 그대로)
#   SKIP_PULL=1 ./deploy.sh web test         레지스트리 대신 서버에 이미 있는 이미지로 (수동 점검용)
#
# 흐름: 태그 기록(tags.env) → pull → up → 헬스체크 → 실패하면 이전 태그로 되돌리고 실패 코드
# 태그는 커밋 sha라 되돌릴 이미지가 레지스트리에 그대로 남아 있다.
set -euo pipefail
cd "$(dirname "$0")"

SERVICE="${1:?서비스(backend|web|crawler|travel|config)를 주세요}"
TAG="${2:-}"
HEALTH_TIMEOUT=300   # 초. 크롤러가 번역 모델(4GB)을 읽는 데 2분쯤 걸린다

compose() { docker compose --env-file .env --env-file tags.env "$@"; }
touch tags.env

case "$SERVICE" in
  backend) VAR=BACKEND_TAG ;;
  web)     VAR=WEB_TAG ;;
  crawler) VAR=CRAWLER_TAG ;;
  travel)  VAR=TRAVEL_TAG ;;
  config)  VAR="" ;;
  *) echo "[deploy] 모르는 서비스: $SERVICE"; exit 2 ;;
esac

set_tag() {  # set_tag <값> — 비우면 줄을 지워 latest로 돌아간다
  grep -v "^$VAR=" tags.env > tags.env.tmp || true
  [[ -n "$1" ]] && echo "$VAR=$1" >> tags.env.tmp
  mv tags.env.tmp tags.env
}

reload_nginx() {  # nginx.conf는 파일 마운트라 컨테이너를 다시 만들지 않고 다시 읽힌다
  if compose exec -T nginx nginx -t -q; then
    compose exec -T nginx nginx -s reload
  else
    echo "[deploy] nginx 설정 오류 — 다시 읽지 않습니다"; return 1
  fi
}

if [[ -z "$VAR" ]]; then
  compose up -d --remove-orphans
  reload_nginx
  echo "[deploy] 설정 적용 완료"
  exit 0
fi

[[ -n "$TAG" ]] || { echo "[deploy] 태그를 주세요"; exit 2; }
PREV="$(grep "^$VAR=" tags.env | cut -d= -f2- || true)"
echo "[deploy] $SERVICE: ${PREV:-latest} → $TAG"

set_tag "$TAG"
[[ "${SKIP_PULL:-}" == 1 ]] || compose pull "$SERVICE"
compose up -d --remove-orphans

wait_healthy() {
  local id status waited=0
  while (( waited < HEALTH_TIMEOUT )); do
    id="$(compose ps -q "$SERVICE")"
    # "<컨테이너 상태> <헬스 상태>" 예: "running healthy", "restarting starting"
    status="$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$id" 2>/dev/null || echo missing)"
    case "$status" in
      "running healthy") return 0 ;;
      exited*|dead*|restarting*|*unhealthy) return 1 ;;   # 바로 죽거나 재시작을 반복하면 기다리지 않는다
    esac
    sleep 5; waited=$((waited + 5))
  done
  return 1
}

if wait_healthy; then
  reload_nginx || true
  docker image prune -f > /dev/null   # 이름표 없는 옛 이미지 정리 (태그 붙은 이전 버전은 남는다)
  echo "[deploy] ✅ $SERVICE $TAG 배포 완료"
  exit 0
fi

echo "[deploy] ❌ $SERVICE $TAG 헬스체크 실패 — ${PREV:-latest}로 되돌립니다"
compose logs --tail 80 "$SERVICE" || true
set_tag "$PREV"
compose up -d "$SERVICE"
# 컨테이너를 다시 만들면 IP가 바뀐다 — nginx가 옛 주소로 502를 내지 않게 다시 읽힌다
wait_healthy && reload_nginx || true
exit 1
