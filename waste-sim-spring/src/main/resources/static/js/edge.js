/*
 * 라즈베리파이 엣지 발열 도메인 — 사이드바 동작.
 *
 * 이 도메인은 장량동과 달리 전용 결과 렌더러가 없다. 서버가 EdgeChatFormatter로
 * 이미 사람이 읽는 텍스트를 만들어 BOT 메시지로 보내기 때문에(ChatController#runEdgeTool)
 * chat.js의 기본 평문 버블이 그대로 맞다 — 그래서 renderMessage()를 두지 않는다.
 * 나중에 온도 시계열을 그래프로 그리고 싶어지면 그때 renderMessage()를 추가하면
 * 되고, 그 변경은 이 파일 안에서 끝난다.
 *
 * 사이드바 입력은 값을 서버로 직접 보내지 않고 <b>자연어 문장으로 조립해 채팅으로
 * 보낸다</b>. 장량동 quickRun()과 같은 방식인데, 이유도 같다: 채팅 경로와 버튼
 * 경로가 같은 파이프라인(EdgeParamGuard → 도구 자체 검증)을 통과하므로 "버튼으로
 * 돌렸을 때와 말로 물었을 때 답이 다른" 상황이 생기지 않는다.
 */

/** 사이드바 select/input 값을 읽어 온다. 사이드바가 없으면 null. */
function getEdgeConfig() {
  const el = id => document.getElementById(id);
  if (!el('eBoard')) return null;
  return {
    board:    el('eBoard').value,
    cooling:  el('eCooling').value,
    workload: el('eWorkload').value,
    ambient:  parseFloat(el('eAmbient').value),
    minutes:  parseInt(el('eMinutes').value, 10)
  };
}

/** 사이드바 조건을 한국어 문장으로 조립해 채팅으로 보낸다. */
function edgeRun() {
  const c = getEdgeConfig();
  if (!c) return;
  const boardLabel   = c.board === 'pi4' ? '라즈베리파이 4' : '라즈베리파이 5';
  const coolingLabel = { bare: '무냉각', passive: '방열판만', active: '팬 냉각' }[c.cooling];
  const workLabel    = c.workload === 'max_throughput' ? '최대 처리량' : '목표 FPS 고정';
  document.getElementById('msgInput').value =
    `${boardLabel}를 ${coolingLabel} 상태에서 ${workLabel}으로 ${c.minutes}분 돌리면 ` +
    `언제 스로틀링이 걸리는지 시뮬레이션해줘. 주변 온도는 ${c.ambient}도야.`;
  send();
}

/** 방열판 배치 비교 — 서버가 표준 후보(HeatsinkPresets)로 비교표를 만들어 준다. */
function edgeHeatsinkCompare() {
  const c = getEdgeConfig();
  if (!c) return;
  // 보드 이름을 하나만 적는다 — "4와 5"처럼 둘 다 들어가면 서버가 보드 비교로 판정하는데,
  // 방열판 도구는 결과가 순위표라 그 경로에서 다루지 않는다.
  const boardLabel = c.board === 'pi4' ? '라즈베리파이 4' : '라즈베리파이 5';
  document.getElementById('msgInput').value =
    `${boardLabel}에서 방열판을 어떤 형상·배치로 붙이는 게 가장 시원한지 비교해줘. ` +
    `주변 온도는 ${c.ambient}도야.`;
  send();
}

/** 회복 정책 3종(R1 완전중지 / R2 저부하 / R3 능동냉각) 비교. */
function edgeRecoveryCompare() {
  const c = getEdgeConfig();
  if (!c) return;
  const boardLabel = c.board === 'pi4' ? '라즈베리파이 4' : '라즈베리파이 5';
  document.getElementById('msgInput').value =
    `${boardLabel}가 스로틀링에 걸린 뒤 회복 정책에 따라 회복 시간(TRT)이 어떻게 달라지는지 알려줘.`;
  send();
}

/** 두 보드를 같은 조건에서 비교한다 — 서버가 board만 바꿔 두 번 실행한다. */
function edgeBoardCompare() {
  const c = getEdgeConfig();
  if (!c) return;
  document.getElementById('msgInput').value =
    `라즈베리파이 4와 5의 발열 특성이 어떻게 다른지 ${edgeConditionPhrase(c)} 비교해줘.`;
  send();
}

/**
 * 사이드바에 보이는 조건을 문장 조각으로 만든다.
 *
 * <p>칩이 고정 문장을 보내면 사용자가 사이드바에서 주변 온도를 30도로 바꿔 놓아도
 * 서버는 기본값 25도로 계산해, <b>화면에 보이는 값과 결과가 어긋난다</b>. 실제로 겪은
 * 혼란이라 칩도 버튼과 같은 조건을 싣게 했다 — 무엇으로 돌렸는지는 답변에도 그대로
 * 남으므로 나중에 결과지를 볼 때 조건을 되짚을 수 있다.
 */
function edgeConditionPhrase(c) {
  const coolingLabel = { bare: '무냉각', passive: '방열판', active: '팬 냉각' }[c.cooling];
  return `${coolingLabel} 상태에서 주변 온도 ${c.ambient}도, ${c.minutes}분 기준으로`;
}

// ── 결과 렌더러 ───────────────────────────────────────────────────
/*
 * 서버가 보내는 EDGE_RESULT에는 사람이 읽는 텍스트(content)와 도구 결과 원본(edgeRuns)이
 * 함께 들어 있다. 여기서는 원본으로 구성도와 표를 그린다 — 텍스트를 다시 파싱하지
 * 않는 이유는 서버 포매터 문구가 바뀔 때마다 화면이 조용히 깨지기 때문이다.
 *
 * 구성도는 3D 대신 <b>측면 단면</b>으로 그린다. 열이 SoC → 서멀 물질 → 방열판 → 공기로
 * 이동하는 경로가 그대로 층으로 보여서, 2노드 모델(SoC↔방열판)이 무엇을 계산하는지가
 * 그림과 1:1로 대응하기 때문이다. 입체로 그리면 예뻐지지만 그 대응이 사라진다.
 */

/** 온도(℃)를 색으로. 소프트 제한 80℃·하드 85℃ 근처에서 확실히 달라지게 나눈다. */
function edgeTempColor(t) {
  if (t == null) return '#8a9698';
  if (t >= 85) return '#c0392b';
  if (t >= 80) return '#d9622b';
  if (t >= 70) return '#d99b2b';
  if (t >= 60) return '#c3b22b';
  if (t >= 50) return '#6f9e3f';
  return '#3f8f7a';
}

function edgeNum(o, k) {
  const v = o && o[k];
  return (typeof v === 'number' && isFinite(v)) ? v : null;
}

/** 실행 하나의 구성(보드·방열판·팬)을 측면 단면 SVG로 그린다. */
function edgeBuildDiagram(run) {
  const m = run.metrics || {};
  const params = run.params || {};
  const peak = edgeNum(m, 'peakTempC');
  const socColor = edgeTempColor(peak);

  const cooling = run.cooling || 'bare';
  const fanRpm = edgeNum(run, 'fanRpm');
  // 0 RPM은 "팬이 없는 조건"으로 그린다 — 캡션이 '팬 없음'인데 팬이 보이면 그림과 글이 어긋난다.
  const hasFan = fanRpm != null ? fanRpm > 0 : cooling === 'active';
  const massG = edgeNum(run, 'heatsinkMassG');
  const hasHeatsink = cooling === 'passive' || cooling === 'active' || hasFan || massG != null;

  // 글꼴·색은 CSS 클래스가 아니라 속성으로 직접 준다 — 클래스에 기대면 스타일시트가
  // 늦게 오거나 캐시된 옛 버전일 때 기본 크기(16px)로 그려져 라벨이 서로 겹친다.
  const LBL = 'font-size="9" fill="#9aa8aa"';
  const W = 320, H = 200;
  const p = [];
  const cx = 140;              // 도면은 왼쪽에, 오른쪽은 라벨 열로 비워 둔다
  const lx = cx + 74;          // 라벨 x — 도면과 겹치지 않는 위치

  if (hasFan) {
    const fy = 14;
    p.push(`<rect x="${cx - 52}" y="${fy}" width="104" height="20" rx="3" fill="#2a78d6" opacity="0.85"/>`);
    for (let i = 0; i < 5; i++) {
      const x = cx - 40 + i * 20;
      p.push(`<line x1="${x}" y1="${fy + 4}" x2="${x}" y2="${fy + 16}" stroke="#fff" stroke-width="2" opacity="0.7"/>`);
    }
    p.push(`<text x="${cx}" y="${fy - 3}" text-anchor="middle" ${LBL}>${
        fanRpm != null ? Math.round(fanRpm) + ' RPM' : '팬'}</text>`);
    for (let i = 0; i < 4; i++) {
      const x = cx - 33 + i * 22;
      p.push(`<path d="M${x} ${fy + 24} L${x} ${fy + 40} M${x - 4} ${fy + 34} L${x} ${fy + 40} L${x + 4} ${fy + 34}"
                stroke="#2a78d6" stroke-width="1.6" fill="none" opacity="0.75"/>`);
    }
  }

  // 층은 아래에서 위로 쌓되 라벨은 층마다 y를 충분히 벌린다(겹침 방지).
  const baseY = 100;
  if (hasHeatsink) {
    for (let i = 0; i < 9; i++) {
      p.push(`<rect x="${cx - 56 + i * 13}" y="${baseY - 30}" width="6" height="30" fill="#9aa8aa"/>`);
    }
    p.push(`<rect x="${cx - 60}" y="${baseY}" width="122" height="9" fill="#7d8b8d"/>`);
    p.push(`<text x="${lx}" y="${baseY + 7}" ${LBL}>${massG != null ? `방열판 ${Math.round(massG)}g` : '방열판'}</text>`);
    p.push(`<rect x="${cx - 30}" y="${baseY + 10}" width="60" height="5" fill="#c9a227"/>`);
    p.push(`<text x="${lx}" y="${baseY + 21}" font-size="9" fill="#6b797b">서멀 물질</text>`);
  }

  const socY = hasHeatsink ? baseY + 15 : baseY;
  p.push(`<rect x="${cx - 30}" y="${socY}" width="60" height="24" rx="2" fill="${socColor}"/>`);
  p.push(`<text x="${cx}" y="${socY + 16}" text-anchor="middle" font-size="10" fill="#fff" font-weight="600">SoC</text>`);
  if (peak != null) {
    p.push(`<text x="${lx}" y="${socY + 17}" font-size="10" fill="${socColor}" font-weight="600">${peak.toFixed(1)}℃</text>`);
  }

  const pcbY = socY + 24;
  p.push(`<rect x="${cx - 96}" y="${pcbY}" width="192" height="10" rx="2" fill="#2f6f4f"/>`);
  p.push(`<text x="${cx}" y="${pcbY + 24}" text-anchor="middle" ${LBL}>${run.board || ''}</text>`);

  return `<svg viewBox="0 0 ${W} ${H}" class="edge-dgm" role="img"
            aria-label="구성 단면도: ${run.board || '보드'}${hasHeatsink ? ', 방열판' : ''}${hasFan ? ', 팬' : ', 팬 없음'}">
            ${p.join('')}</svg>`;
}

/** 실행 조건을 칩 목록으로 — 주변 온도·측정 시간처럼 결과를 좌우하는 값을 앞에 세운다. */
function edgeConditionChips(run) {
  const chips = [];
  const amb = edgeNum(run, 'ambientTempC');
  const load = edgeNum(run, 'loadSeconds');
  if (amb != null) chips.push(['주변 온도', amb + '℃']);
  if (load != null) chips.push(['측정 시간', Math.round(load / 60) + '분']);
  const modeKo = { max_throughput: '최대 처리량', target_fps: '목표 FPS 유지' };
  if (run.workloadMode) chips.push(['워크로드', modeKo[run.workloadMode] || run.workloadMode]);
  const coolKo = { bare: '무냉각', passive: '방열판', active: '팬 냉각' };
  if (run.cooling) chips.push(['냉각', coolKo[run.cooling] || run.cooling]);
  if (run.aiLoadProfile && run.aiLoadProfile.label) chips.push(['부하 패턴', run.aiLoadProfile.label]);
  return chips.map(([k, v]) =>
    `<span class="edge-chip"><b>${k}</b> ${v}</span>`).join('');
}

const EDGE_ROWS = [
  ['tttSec',            '스로틀링 진입(TTT)', v => v == null ? '발생 안 함' : v + '초'],
  ['softLimitEntrySec', '소프트 제한(80℃) 진입', v => v == null ? '도달 안 함' : v + '초'],
  ['peakTempC',         '최고 온도',          v => v == null ? '-' : v + '℃'],
  ['steadyStateTempC',  '정상상태 예상 온도',  v => v == null ? '-' : v + '℃'],
  ['medianTedSec',      'TED 중앙값',         v => v == null ? '-' : v + '초'],
  ['trtStateSec',       '회복(TRT_state)',    v => v == null ? '-' : v + '초'],
  ['meanFpsDuringLoad', '부하 중 평균 FPS',   v => v == null ? '-' : v],
  // 처리량 손실을 FPS 낙폭보다 위에 둔다 — TTT가 "발생 안 함"이어도 소프트 제한만으로
  // 성능이 깎이는데, 그 사실이 표에서 가장 먼저 보여야 오독을 막는다.
  ['throughputLossPercent', '처리량 손실(지속)', v => v == null ? '-' : v + '%'],
  ['fpsDropPercent',    'FPS 낙폭(최악 순간)', v => v == null ? '-' : v + '%'],
  ['tauHeatingSec',     '가열 시정수 τ',      v => v == null ? '-' : v + '초'],
  ['energyJ',           'SoC 소비 에너지',    v => v == null ? '-' : Math.round(v) + 'J'],
  ['fanEnergyJ',        '팬 소비 에너지',     v => v == null ? null : Math.round(v) + 'J'],
  ['totalEnergyJ',      '총 소비 에너지',     v => v == null ? null : Math.round(v) + 'J']
];

/** 지표를 표로. 비교 실행이면 열이 둘이 된다. */
function edgeBuildTable(runs, names) {
  const head = names.length > 1
    ? `<tr><th>지표</th>${names.map(n => `<th>${n}</th>`).join('')}</tr>`
    : '<tr><th>지표</th><th>값</th></tr>';
  const body = EDGE_ROWS.map(([key, label, fmt]) => {
    const cells = runs.map(r => fmt(edgeNum(r.metrics || {}, key)));
    // 어느 실행에도 값이 없는 지표(팬을 안 썼을 때의 팬 에너지 등)는 행 자체를 뺀다.
    if (cells.every(c => c === null)) return '';
    return `<tr><td>${label}</td>${cells.map(c => `<td>${c == null ? '-' : c}</td>`).join('')}</tr>`;
  }).join('');
  return `<table class="edge-tbl">${head}${body}</table>`;
}

/** 비교 실행일 때 각 열에 붙일 이름 — 무엇이 달랐는지가 드러나야 한다. */
function edgeRunNames(runs) {
  if (runs.length < 2) return [''];
  const a = runs[0], b = runs[1];
  if (a.board !== b.board) return [a.board, b.board];
  const fa = edgeNum(a, 'fanRpm'), fb = edgeNum(b, 'fanRpm');
  if (fa !== fb) return ['팬 없음', '팬 가동'];
  if (a.heatsinkMaterial !== b.heatsinkMaterial) return ['알루미늄', '구리'];
  return ['A', 'B'];
}

function edgeBuildResultBubble(msg) {
  const runs = msg.edgeRuns || [];
  const div = document.createElement('div');
  div.className = 'msg bot edge-result';

  if (!runs.length) {                    // 원본이 없으면 텍스트만 — 안전한 폴백
    div.textContent = msg.content;
    return div;
  }
  const names = edgeRunNames(runs);
  const diagrams = runs.map((r, i) => `
    <figure class="edge-dgm-wrap">
      ${runs.length > 1 ? `<figcaption>${names[i]}</figcaption>` : ''}
      ${edgeBuildDiagram(r)}
    </figure>`).join('');

  div.innerHTML =
    `<div class="edge-cond">${edgeConditionChips(runs[0])}</div>` +
    `<div class="edge-dgms">${diagrams}</div>` +
    edgeBuildTable(runs, names) +
    `<details class="edge-notes"><summary>해석과 주의사항</summary><pre>${
        (msg.content || '').replace(/[<>&]/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[c]))
    }</pre></details>`;
  return div;
}

// ── 도메인 등록 ───────────────────────────────────────────────────
Domains.register({
  id: 'edge',
  icon: '🌡️',
  label: '라즈베리파이 엣지 발열',
  tagline: '보드·워크로드·냉각 조건에 따른 발열과 스로틀링 시점을 예측합니다.',
  title: '라즈베리파이 엣지 발열 시뮬레이션',
  placeholder: '보드·냉각 조건을 자연어로 입력하세요... (Shift+Enter: 줄바꿈, Enter: 전송)',
  greeting:
    '라즈베리파이 엣지 발열 시뮬레이션입니다.\n\n' +
    '고부하 AI 추론 중 SoC 온도가 어떻게 오르는지, 언제 스로틀링에 걸리고(TTT) ' +
    '얼마나 지속되며(TED) 얼마 만에 회복되는지(TRT)를 열 RC 모델로 예측합니다.\n\n' +
    '기본값은 문헌 기반 추정치입니다 — 실측 CSV로 보정하려면 "실측 데이터로 모델 보정해줘"라고 물어보세요.',
  // 조건이 결과를 바꾸는 칩은 전부 run()으로 둔다 — text로 고정 문장을 넣으면
  // 사이드바 값이 반영되지 않아 화면과 결과가 어긋난다.
  chips: [
    { label: 'Pi5 무냉각 20분', text: '라즈베리파이 5 무냉각으로 20분 돌리면 언제 스로틀링 걸려?' },
    { label: '방열판 효과', run: () => edgeHeatsinkCompare() },
    { label: '회복 정책 비교', run: () => edgeRecoveryCompare() },
    { label: 'Pi4 vs Pi5', run: () => edgeBoardCompare() },
    { label: '실측 보정 방법', text: '실측 데이터로 열 모델을 보정하려면 어떻게 해?' }
  ],
  renderMessage(msg) {
    if (msg.type === 'EDGE_RESULT') {
      document.getElementById('messages').appendChild(edgeBuildResultBubble(msg));
      return true;
    }
    return false;
  }
});
