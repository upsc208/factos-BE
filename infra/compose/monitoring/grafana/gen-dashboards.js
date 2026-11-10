// Grafana 대시보드 JSON 생성기 — 대시보드의 원본은 이 파일이다. 고친 뒤: node gen-dashboards.js dashboards
const fs = require('fs');
const path = require('path');
const out = process.argv[2];

const PROM = { type: 'prometheus', uid: 'prometheus' };
const FDB = { type: 'grafana-postgresql-datasource', uid: 'factos-db' };
const TDB = { type: 'grafana-postgresql-datasource', uid: 'travel-db' };

let id = 1;
function layout(rows) {
  // rows: [[panel, w], ...] per row, 24 columns
  const panels = [];
  let y = 0;
  for (const row of rows) {
    if (row.title) { panels.push({ id: id++, type: 'row', title: row.title, collapsed: false, gridPos: { h: 1, w: 24, x: 0, y }, panels: [] }); y += 1; continue; }
    let x = 0; let hMax = 0;
    for (const [p, w] of row) { p.gridPos = { h: p.h || 8, w, x, y }; delete p.h; x += w; hMax = Math.max(hMax, p.gridPos.h); panels.push(p); }
    y += hMax;
  }
  return panels;
}
const prom = (expr, legend = '', extra = {}) => ({ datasource: PROM, expr, legendFormat: legend, refId: String.fromCharCode(65 + (extra.i || 0)), ...(extra.instant ? { instant: true, range: false } : { instant: false, range: true }), ...(extra.format ? { format: extra.format } : {}) });
const sql = (ds, rawSql, format = 'table') => ({ datasource: ds, rawSql, format, rawQuery: true, editorMode: 'code', refId: 'A' });

function ts(title, targets, unit = 'short', extra = {}) {
  return { id: id++, type: 'timeseries', title, datasource: targets[0].datasource, targets,
    fieldConfig: { defaults: { unit, custom: { lineWidth: 1, fillOpacity: extra.stack ? 60 : 10, showPoints: 'never', stacking: { mode: extra.stack ? 'normal' : 'none' }, drawStyle: extra.bars ? 'bars' : 'line' }, ...(extra.min != null ? { min: extra.min } : {}), ...(extra.max != null ? { max: extra.max } : {}) }, overrides: [] },
    options: { legend: { displayMode: 'list', placement: 'bottom' }, tooltip: { mode: 'multi', sort: 'desc' } }, description: extra.desc || '' };
}
function stat(title, target, unit = 'short', extra = {}) {
  return { id: id++, type: 'stat', title, datasource: target.datasource, targets: [target], h: 4,
    fieldConfig: { defaults: { unit, thresholds: { mode: 'absolute', steps: extra.steps || [{ color: 'blue', value: null }] }, ...(extra.mappings ? { mappings: extra.mappings } : {}) }, overrides: [] },
    options: { reduceOptions: { calcs: ['lastNotNull'], fields: '', values: false }, colorMode: extra.colorMode || 'value', graphMode: 'none', textMode: 'auto', justifyMode: 'auto' }, description: extra.desc || '' };
}
function table(title, target, extra = {}) {
  return { id: id++, type: 'table', title, datasource: target.datasource, targets: [target], h: extra.h || 9,
    fieldConfig: { defaults: { custom: { align: 'auto', cellOptions: { type: 'auto' } } }, overrides: extra.overrides || [] },
    options: { showHeader: true, cellHeight: 'sm' }, transformations: extra.transformations || [], description: extra.desc || '' };
}
function bar(title, target, extra = {}) {
  return { id: id++, type: 'barchart', title, datasource: target.datasource, targets: [target], h: extra.h || 8,
    fieldConfig: { defaults: { unit: 'short', custom: { fillOpacity: 80 } }, overrides: [] },
    options: { orientation: extra.horizontal ? 'horizontal' : 'auto', showValue: 'auto', xTickLabelRotation: 0, legend: { showLegend: false, displayMode: 'list', placement: 'bottom' }, tooltip: { mode: 'single' } }, description: extra.desc || '' };
}
function pie(title, target) {
  return { id: id++, type: 'piechart', title, datasource: target.datasource, targets: [target], h: 8,
    fieldConfig: { defaults: { unit: 'short' }, overrides: [] },
    options: { reduceOptions: { calcs: ['lastNotNull'], fields: '', values: true }, pieType: 'donut', legend: { displayMode: 'table', placement: 'right', values: ['value', 'percent'] }, displayLabels: [] } };
}
function dash(uid, title, tags, rows, extra = {}) {
  id = 1;
  const panels = layout(rows());
  return { uid, title, tags, timezone: 'browser', schemaVersion: 39, version: 1, editable: false, graphTooltip: 1,
    time: { from: extra.from || 'now-24h', to: 'now' }, refresh: extra.refresh || '1m', templating: { list: extra.vars || [] }, annotations: { list: [] }, panels };
}

// ── 1. 서버 상태 (Prometheus) ─────────────────────────────
const UP = [{ type: 'value', options: { 0: { text: '꺼짐', color: 'red' }, 1: { text: '정상', color: 'green' } } }];
const SVC = 'application=~"backend|travel"';
const system = dash('factos-system', '서버 상태', ['factos', 'ops'], () => [
  [[stat('백엔드', prom('up{job="backend"}', '', { instant: true }), 'none', { mappings: UP, colorMode: 'background' }), 4],
   [stat('축구 여행', prom('up{job="travel"}', '', { instant: true }), 'none', { mappings: UP, colorMode: 'background' }), 4],
   [stat('요청 / 초 (5분)', prom(`sum(rate(http_server_requests_seconds_count{${SVC}}[5m]))`, '', { instant: true }), 'reqps'), 4],
   [stat('5xx 비율 (5분)', prom(`(sum(rate(http_server_requests_seconds_count{${SVC},status=~"5.."}[5m])) or vector(0)) / clamp_min(sum(rate(http_server_requests_seconds_count{${SVC}}[5m])), 1e-9)`, '', { instant: true }), 'percentunit', { steps: [{ color: 'green', value: null }, { color: 'orange', value: 0.01 }, { color: 'red', value: 0.05 }] }), 4],
   [stat('서버 CPU', prom('1 - avg(rate(node_cpu_seconds_total{mode="idle"}[5m]))', '', { instant: true }), 'percentunit', { steps: [{ color: 'green', value: null }, { color: 'orange', value: 0.7 }, { color: 'red', value: 0.9 }] }), 4],
   [stat('서버 디스크', prom('1 - node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"}', '', { instant: true }), 'percentunit', { steps: [{ color: 'green', value: null }, { color: 'orange', value: 0.75 }, { color: 'red', value: 0.9 }] }), 4]],
  { title: 'API' },
  [[ts('요청 / 초', [prom(`sum by (application) (rate(http_server_requests_seconds_count{${SVC}}[5m]))`, '{{application}}')], 'reqps'), 8],
   [ts('응답 시간 p95', [prom(`histogram_quantile(0.95, sum by (le, application) (rate(http_server_requests_seconds_bucket{${SVC}}[5m])))`, '{{application}}')], 's'), 8],
   [ts('오류 응답 / 분 (4xx·5xx)', [prom(`sum by (application, status) (rate(http_server_requests_seconds_count{${SVC},status=~"[45].."}[5m])) * 60`, '{{application}} {{status}}')], 'short'), 8]],
  [[table('느린 API (최근 1시간 p95)', prom(`topk(15, histogram_quantile(0.95, sum by (le, application, method, uri) (rate(http_server_requests_seconds_bucket{${SVC},uri!~"/actuator.*"}[1h]))))`, '', { instant: true, format: 'table' }),
     { transformations: [{ id: 'organize', options: { excludeByName: { Time: true }, renameByName: { application: '서비스', method: '메서드', uri: '주소', Value: 'p95(초)' } } }, { id: 'sortBy', options: { sort: [{ field: 'p95(초)', desc: true }] } }] }), 12],
   [table('많이 불린 API (최근 1시간)', prom(`topk(15, sum by (application, method, uri) (increase(http_server_requests_seconds_count{${SVC},uri!~"/actuator.*"}[1h])))`, '', { instant: true, format: 'table' }),
     { transformations: [{ id: 'organize', options: { excludeByName: { Time: true }, renameByName: { application: '서비스', method: '메서드', uri: '주소', Value: '횟수' } } }, { id: 'sortBy', options: { sort: [{ field: '횟수', desc: true }] } }] }), 12]],
  { title: '크롤러 (본문 추출 · 번역)' },
  [[ts('크롤러 호출 / 시간', [prom('sum by (op, outcome) (increase(factos_crawler_seconds_count[1h]))', '{{op}} {{outcome}}')], 'short', { bars: true, desc: 'outcome: ok 성공 · unavailable 번역 모델 없음 · error 실패' }), 12],
   [ts('크롤러 평균 걸린 시간', [prom('sum by (op) (rate(factos_crawler_seconds_sum{outcome="ok"}[30m])) / sum by (op) (rate(factos_crawler_seconds_count{outcome="ok"}[30m]))', '{{op}}')], 's'), 12]],
  { title: 'JVM · DB 연결' },
  [[ts('힙 메모리', [prom('sum by (application) (jvm_memory_used_bytes{area="heap"})', '{{application}} 사용', { i: 0 }), prom('sum by (application) (jvm_memory_max_bytes{area="heap"})', '{{application}} 최대', { i: 1 })], 'bytes'), 8],
   [ts('GC 멈춤 시간 / 분', [prom('sum by (application) (rate(jvm_gc_pause_seconds_sum[5m])) * 60', '{{application}}')], 's'), 8],
   [ts('DB 연결 (Hikari)', [prom('sum by (application) (hikaricp_connections_active)', '{{application}} 사용 중', { i: 0 }), prom('sum by (application) (hikaricp_connections_pending)', '{{application}} 대기', { i: 1 })], 'short'), 8]],
  { title: '컨테이너 · 서버' },
  [[ts('컨테이너 CPU (코어)', [prom('sum by (name) (rate(container_cpu_usage_seconds_total{name!=""}[5m]))', '{{name}}')], 'short'), 12],
   [ts('컨테이너 메모리', [prom('sum by (name) (container_memory_working_set_bytes{name!=""})', '{{name}}')], 'bytes'), 12]],
  [[ts('서버 메모리 사용률', [prom('1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes', '사용률')], 'percentunit', { min: 0, max: 1 }), 8],
   [ts('서버 부하 (load 1분)', [prom('node_load1', 'load1')], 'short'), 8],
   [ts('네트워크', [prom('sum(rate(node_network_receive_bytes_total{device!~"lo|veth.*|docker.*|br-.*"}[5m]))', '받음', { i: 0 }), prom('sum(rate(node_network_transmit_bytes_total{device!~"lo|veth.*|docker.*|br-.*"}[5m]))', '보냄', { i: 1 })], 'Bps'), 8]],
], { from: 'now-6h', refresh: '30s' });

// ── 2. 유저 · 기사 (factos DB) ─────────────────────────────
const CLUB_KO = `CASE lower(%s) WHEN 'arsenal' THEN '아스날' WHEN 'chelsea' THEN '첼시' WHEN 'liverpool' THEN '리버풀' WHEN 'mancity' THEN '맨시티' WHEN 'man_city' THEN '맨시티' WHEN 'manutd' THEN '맨유' WHEN 'man_utd' THEN '맨유' WHEN 'tottenham' THEN '토트넘' WHEN 'other' THEN '기타' ELSE coalesce(%s, '미정') END`;
const clubKo = (col) => CLUB_KO.replace(/%s/g, col);
const KST = (col) => `date_trunc('day', ${col} AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul'`;
const community = dash('factos-community', '유저 · 기사', ['factos', 'business'], () => [
  { title: '회원' },
  [[stat('가입 회원', sql(FDB, `SELECT count(*) FROM member WHERE signup_completed`)), 4],
   [stat('오늘 가입 (KST)', sql(FDB, `SELECT count(*) FROM member WHERE signup_completed AND created_at >= ${KST('now()')}`)), 4],
   [stat('24시간 안에 로그인', sql(FDB, `SELECT count(*) FROM member WHERE last_login_at > now() - interval '24 hours'`), 'short', { desc: '로그인할 때만 갱신된다 (쿠키로 계속 들어오는 사람은 안 셈) — 대략적인 활동 회원' }), 4],
   [stat('7일 안에 로그인', sql(FDB, `SELECT count(*) FROM member WHERE last_login_at > now() - interval '7 days'`)), 4],
   [stat('가입 중단 (닉네임 안 정함)', sql(FDB, `SELECT count(*) FROM member WHERE NOT signup_completed`), 'short', { desc: '첫 로그인만 하고 /signup을 마치지 않은 계정' }), 4],
   [stat('정지 중', sql(FDB, `SELECT count(DISTINCT member_id) FROM member_suspension WHERE lifted_at IS NULL AND ends_at > now()`), 'short', { steps: [{ color: 'green', value: null }, { color: 'orange', value: 1 }] }), 4]],
  [[ts('일별 가입 · 로그인 (KST)', [sql(FDB, `SELECT ${KST('created_at')} AS time, count(*) AS "가입" FROM member WHERE signup_completed AND $__timeFilter(created_at) GROUP BY 1 ORDER BY 1`, 'time_series')], 'short', { bars: true }), 12],
   [pie('응원팀', sql(FDB, `SELECT ${clubKo('favorite_team')} AS "팀", count(*) AS "회원" FROM member WHERE signup_completed GROUP BY 1 ORDER BY 2 DESC`)), 6],
   [pie('로그인 방식', sql(FDB, `SELECT provider AS "방식", count(*) AS "회원" FROM member GROUP BY 1`)), 6]],
  { title: '기사' },
  [[stat('기간 안 수집 기사', sql(FDB, `SELECT count(*) FROM news_article WHERE $__timeFilter(created_at)`)), 4],
   [stat('번역 대기', sql(FDB, `SELECT count(*) FROM news_article WHERE translation_status = 'PENDING'`), 'short', { steps: [{ color: 'green', value: null }, { color: 'orange', value: 20 }, { color: 'red', value: 100 }], desc: '크롤러 번역을 기다리는 기사' }), 4],
   [stat('번역 완료율 (기간)', sql(FDB, `SELECT avg(CASE WHEN translation_status = 'DONE' THEN 1.0 ELSE 0 END) FROM news_article WHERE $__timeFilter(created_at)`), 'percentunit'), 4],
   [stat('기간 안 조회수', sql(FDB, `SELECT coalesce(sum(view_count), 0) FROM news_article WHERE $__timeFilter(created_at)`), 'short', { desc: '그 기간에 수집된 기사들의 누적 조회수' }), 4],
   [stat('수집 실패 (기간)', sql(FDB, `SELECT count(*) FROM collection_log WHERE status = 'FAIL' AND $__timeFilter(created_at)`), 'short', { steps: [{ color: 'green', value: null }, { color: 'red', value: 1 }] }), 4],
   [stat('마지막 수집', sql(FDB, `SELECT extract(epoch FROM now() - max(created_at)) FROM collection_log`), 's', { steps: [{ color: 'green', value: null }, { color: 'orange', value: 3600 }, { color: 'red', value: 7200 }], desc: '15분마다 돈다 — 1시간 넘게 비면 수집 루프를 확인' }), 4]],
  [[ts('팀별 수집 기사 (일별, KST)', [sql(FDB, `SELECT ${KST('a.created_at')} AS time, ${clubKo('c.club')} AS metric, count(*) AS value FROM news_article a JOIN news_article_club c ON c.article_id = a.id WHERE $__timeFilter(a.created_at) GROUP BY 1, 2 ORDER BY 1`, 'time_series')], 'short', { bars: true, stack: true }), 12],
   [bar('팀별 조회수 (기간)', sql(FDB, `SELECT ${clubKo('c.club')} AS "팀", sum(a.view_count) AS "조회수" FROM news_article a JOIN news_article_club c ON c.article_id = a.id WHERE $__timeFilter(a.created_at) GROUP BY 1 ORDER BY 2 DESC`)), 6],
   [bar('출처별 기사 (기간)', sql(FDB, `SELECT coalesce(source_name, '?') AS "출처", count(*) AS "기사" FROM news_article WHERE $__timeFilter(created_at) GROUP BY 1 ORDER BY 2 DESC LIMIT 12`), { horizontal: true }), 6]],
  [[table('많이 본 기사 (기간)', sql(FDB, `SELECT coalesce(a.title_ko, a.title_en) AS "제목", string_agg(DISTINCT ${clubKo('c.club')}, ', ') AS "팀", a.source_name AS "출처", a.view_count AS "조회", (SELECT count(*) FROM article_comment m WHERE m.article_id = a.id) AS "댓글", (SELECT count(*) FROM bookmark b WHERE b.article_id = a.id) AS "즐겨찾기", a.published_at AS "발행" FROM news_article a LEFT JOIN news_article_club c ON c.article_id = a.id WHERE $__timeFilter(a.created_at) GROUP BY a.id ORDER BY a.view_count DESC LIMIT 20`), { h: 10 }), 24]],
  { title: '댓글 · 신고' },
  [[ts('일별 댓글 (KST)', [sql(FDB, `SELECT ${KST('created_at')} AS time, count(*) AS "댓글" FROM article_comment WHERE $__timeFilter(created_at) GROUP BY 1 ORDER BY 1`, 'time_series')], 'short', { bars: true }), 8],
   [table('댓글 많이 쓴 회원 (기간)', sql(FDB, `SELECT m.nickname AS "닉네임", ${clubKo('m.favorite_team')} AS "응원팀", count(*) AS "댓글" FROM article_comment c JOIN member m ON m.id = c.member_id WHERE $__timeFilter(c.created_at) GROUP BY m.id, m.nickname, m.favorite_team ORDER BY 3 DESC LIMIT 10`), { h: 8 }), 8],
   [table('신고', sql(FDB, `SELECT CASE status WHEN 'OPEN' THEN '대기' WHEN 'RESOLVED' THEN '처리' WHEN 'DISMISSED' THEN '문제없음' ELSE status END AS "상태", reason AS "사유", count(*) AS "건수" FROM comment_report WHERE $__timeFilter(created_at) OR status = 'OPEN' GROUP BY status, reason ORDER BY status, 3 DESC`), { h: 8 }), 8]],
  { title: '수집 · 평점' },
  [[ts('수집 1회 걸린 시간', [sql(FDB, `SELECT created_at AS time, execution_time_ms / 1000.0 AS "초" FROM collection_log WHERE $__timeFilter(created_at) ORDER BY 1`, 'time_series')], 's'), 12],
   [table('최근 수집 실패', sql(FDB, `SELECT created_at AS "시각", left(error_msg, 200) AS "오류" FROM collection_log WHERE status = 'FAIL' AND $__timeFilter(created_at) ORDER BY created_at DESC LIMIT 20`), { h: 8 }), 12]],
  [[table('팬 평점 투표 (기간)', sql(FDB, `SELECT match_id AS "경기", count(*) AS "투표", count(DISTINCT player_name) AS "선수", round(avg(score)::numeric, 2) AS "평균 점수" FROM player_rating_vote WHERE $__timeFilter(created_at) GROUP BY match_id ORDER BY 2 DESC LIMIT 15`), { h: 8 }), 24]],
], { from: 'now-30d', refresh: '5m' });

// ── 3. 축구 여행 (travel DB) ─────────────────────────────
const travel = dash('factos-travel', '축구 여행', ['factos', 'business', 'travel'], () => [
  [[stat('여행 글 (기간)', sql(TDB, `SELECT count(*) FROM post WHERE $__timeFilter(created_at)`)), 4],
   [stat('여행 댓글 (기간)', sql(TDB, `SELECT count(*) FROM post_comment WHERE $__timeFilter(created_at)`)), 4],
   [stat('처리할 제보', sql(TDB, `SELECT count(*) FROM guide_report WHERE status = 'PENDING'`), 'short', { steps: [{ color: 'green', value: null }, { color: 'orange', value: 1 }, { color: 'red', value: 30 }] }), 4],
   [stat('팀 팬 펍', sql(TDB, `SELECT count(*) FROM pub WHERE club IS NOT NULL AND NOT hidden`)), 4],
   [stat('가격 등록 항목', sql(TDB, `SELECT count(*) FROM stadium_item`)), 4],
   [stat('1년 넘은 가격', sql(TDB, `SELECT count(*) FROM stadium_item WHERE checked_on < current_date - interval '1 year'`), 'short', { steps: [{ color: 'green', value: null }, { color: 'orange', value: 1 }] }), 4]],
  [[ts('일별 여행 글 (KST)', [sql(TDB, `SELECT ${KST('created_at')} AS time, CASE category WHEN 'REVIEW' THEN '직관 후기' WHEN 'TIP' THEN '여행 팁' WHEN 'QUESTION' THEN '질문' ELSE '자유' END AS metric, count(*) AS value FROM post WHERE $__timeFilter(created_at) GROUP BY 1, 2 ORDER BY 1`, 'time_series')], 'short', { bars: true, stack: true }), 12],
   [bar('팀별 여행 글 (기간)', sql(TDB, `SELECT ${clubKo('club')} AS "팀", count(*) AS "글" FROM post WHERE $__timeFilter(created_at) GROUP BY 1 ORDER BY 2 DESC`)), 12]],
  [[table('제보', sql(TDB, `SELECT CASE kind WHEN 'ITEM' THEN '경기장 가격' ELSE '펍' END AS "종류", ${clubKo('club')} AS "팀", CASE status WHEN 'PENDING' THEN '대기' WHEN 'APPROVED' THEN '승인' ELSE '거절' END AS "상태", count(*) AS "건수" FROM guide_report WHERE $__timeFilter(created_at) OR status = 'PENDING' GROUP BY 1, 2, 3 ORDER BY 3, 4 DESC`), { h: 8 }), 8],
   [table('지역별 펍', sql(TDB, `SELECT city AS "지역", count(*) AS "펍", count(*) FILTER (WHERE shows_football) AS "중계", count(*) FILTER (WHERE club IS NOT NULL) AS "팀 팬 펍", max(updated_at) AS "마지막 갱신" FROM pub WHERE NOT hidden GROUP BY city ORDER BY 2 DESC`), { h: 8, desc: 'OpenStreetMap에서 매달 1일 다시 받는다' }), 8],
   [table('팀별 가격 항목', sql(TDB, `SELECT ${clubKo('club')} AS "팀", count(*) AS "항목", max(checked_on) AS "최근 확인" FROM stadium_item GROUP BY club ORDER BY 2 DESC`), { h: 8 }), 8]],
  [[table('댓글 많은 여행 글 (기간)', sql(TDB, `SELECT p.title AS "제목", ${clubKo('p.club')} AS "팀", p.category AS "말머리", p.comment_count AS "댓글", m.nickname AS "글쓴이", p.created_at AS "작성" FROM post p LEFT JOIN member_profile m ON m.member_id = p.author_id WHERE $__timeFilter(p.created_at) ORDER BY p.comment_count DESC, p.id DESC LIMIT 15`), { h: 9 }), 24]],
], { from: 'now-30d', refresh: '5m' });

for (const [name, d] of [['system', system], ['community', community], ['travel', travel]]) {
  fs.writeFileSync(path.join(out, `${name}.json`), JSON.stringify(d, null, 2) + '\n');
  console.log(name, d.panels.length, 'panels');
}
