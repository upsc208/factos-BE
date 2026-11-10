#!/bin/sh
# Grafana용 읽기 전용 DB 계정(grafana_ro)을 두 DB에 만든다. compose의 grafana-db-init이 뜰 때마다 돌린다 (여러 번 돌려도 같다).
# - 비밀번호는 GRAFANA_DB_PASSWORD. 바꾸면 다음 기동 때 반영된다
# - 읽기만: 트랜잭션 기본값 read only + 쿼리 10초 제한
# - 개인 식별 값(소셜 계정 id, 평점 투표 IP)은 열 단위로 막는다
set -eu

: "${GRAFANA_DB_PASSWORD:?GRAFANA_DB_PASSWORD가 비어 있습니다 (.env)}"
: "${GRAFANA_ADMIN_PASSWORD:?GRAFANA_ADMIN_PASSWORD가 비어 있습니다 (.env) — 비면 Grafana 기본 비밀번호(admin)로 뜬다}"

grant() {  # $1 호스트, $2 DB, $3 소유 계정, $4 그 비밀번호, $5 추가 SQL
  echo "[grafana-db-init] $1/$2"
  PGPASSWORD="$4" psql -h "$1" -U "$3" -d "$2" -v ON_ERROR_STOP=1 -q \
    -v pw="$GRAFANA_DB_PASSWORD" -v db="$2" -v owner="$3" <<SQL
SELECT format('CREATE ROLE grafana_ro LOGIN PASSWORD %L', :'pw')
  WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grafana_ro') \gexec
SELECT format('ALTER ROLE grafana_ro PASSWORD %L', :'pw') \gexec
ALTER ROLE grafana_ro SET default_transaction_read_only = on;
ALTER ROLE grafana_ro SET statement_timeout = '10s';
GRANT CONNECT ON DATABASE :"db" TO grafana_ro;
GRANT USAGE ON SCHEMA public TO grafana_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO grafana_ro;
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public GRANT SELECT ON TABLES TO grafana_ro;
$5
SQL
}

# factos DB: 회원은 통계에 쓰는 열만, 평점 투표는 IP 빼고
grant postgres "$POSTGRES_DB" "$POSTGRES_USER" "$POSTGRES_PASSWORD" "
DO \$\$BEGIN
  IF to_regclass('public.member') IS NOT NULL THEN
    REVOKE SELECT ON member FROM grafana_ro;
    GRANT SELECT (id, nickname, provider, role, admin_club, favorite_team, signup_completed, created_at, last_login_at) ON member TO grafana_ro;
  END IF;
  IF to_regclass('public.player_rating_vote') IS NOT NULL THEN
    REVOKE SELECT ON player_rating_vote FROM grafana_ro;
    GRANT SELECT (id, match_id, player_name, shirt, score, created_at) ON player_rating_vote TO grafana_ro;
  END IF;
END\$\$;"

grant travel-db travel travel "$TRAVEL_DB_PASSWORD" ""

echo "[grafana-db-init] 완료"
