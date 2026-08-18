/*
 * 장량동 생활쓰레기 수거 도메인 — 사이드바 동작과 도메인 고유 결과 렌더링.
 *
 * 도메인 분리 이전 index.html에 있던 코드를 그대로 옮긴 것이라 계산·차트 로직은
 * 동일하다. 달라진 점은 두 가지다.
 *   1. 최상단 즉시 실행 코드(#pTime 리스너)를 init()으로 옮겼다 — 사이드바가
 *      <template>에서 DOM에 붙은 뒤에야 존재하는 요소이기 때문이다.
 *   2. RESULT/SCENARIO 메시지 처리를 renderMessage()로 노출해 chat.js가 도메인
 *      이름을 몰라도 위임할 수 있게 했다.
 */

let sideChart = null;

/*
 * 색은 app.css의 :root 토큰에서 읽는다 — 예전엔 여기에 '#5c73f2' 같은 값이 직접
 * 박혀 있어서, CSS 팔레트를 바꿔도 차트와 막대만 옛 색으로 남았다. 색의 진실
 * 원천을 CSS 한 곳으로 두면 그 어긋남이 구조적으로 생기지 않는다.
 */
const CSS = name => getComputedStyle(document.documentElement)
        .getPropertyValue(name).trim() || '#8b9099';

/** 토큰 색 + 알파(0~1) → rgba. 차트 채움처럼 반투명이 필요한 자리에 쓴다. */
function CSSa(name, alpha) {
  const hex = CSS(name);
  const m = /^#([0-9a-f]{6})$/i.exec(hex);
  if (!m) return hex;
  const n = parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

function buildResultBubble(msg) {
  const r = msg.simulationResult;
  const cfg = msg.simulationConfig;

  const div = document.createElement('div');
  div.className = 'msg result';

  const bubble = document.createElement('div');
  bubble.className = 'bubble';

  const header = document.createElement('div');
  header.className = 'result-header';
  header.textContent = `시뮬레이션 결과 — 수거시각 ${r.collectionTimeLabel || cfg?.collectionTimeLabel}`;
  bubble.appendChild(header);

  // 통계
  //
  // 색은 "필드"가 아니라 "값"에 붙인다. 예전엔 잔류량·미수거·교통 패널티 칸에
  // color:var(--yellow)가 항상 걸려 있어서, 미수거가 0회인 정상 실행에서도 그 칸이
  // 경고색으로 물들었다 — 지표 12개 중 6개가 상시 노란색이면 색이 신호가 아니라
  // 배경이 되고, 정작 진짜로 0이 아닌 날에 눈이 가지 않는다.
  const stats = document.createElement('div');
  stats.className = 'result-stats';

  /** 값이 실제로 0을 넘을 때만 경고색을 입힌다. 그 외에는 평범한 본문색. */
  const warn = v => (Number(v) > 0 ? ' style="color:var(--warn)"' : '');
  /** 실행 조건(기간·시드)은 계측값이 아니라 메타데이터라 항상 낮은 대비로 둔다. */
  const meta = ' style="color:var(--muted)"';
  const cell = (valueHtml, label) =>
      `<div class="result-stat"><div class="v"${valueHtml.attr ?? ''}>${valueHtml.text}</div>` +
      `<div class="l">${label}</div></div>`;
  const num = (v, digits, suffix = '') =>
      v == null ? '—' : Number(v).toFixed(digits) + suffix;

  stats.innerHTML = [
    cell({ text: num(r.meanComplaints, 1) }, '평균 생활 민원'),
    cell({ text: num(r.meanWasteOverflowComplaints, 1) }, '적재 초과 민원'),
    cell({ text: num(r.meanLandlordComplaints, 1) }, '임대인 민원'),
    cell({ text: num(r.collectedWasteKg, 1, 'kg') }, '평균 수거량'),
    cell({ text: num(r.residualWasteKg, 1, 'kg'), attr: warn(r.residualWasteKg) }, '평균 잔류량'),
    cell({ text: num(r.truckUtilizationPercent, 1, '%') }, '트럭 이용률'),
    cell({ text: r.unservedPickupCount ?? '—', attr: warn(r.unservedPickupCount) }, '미수거 방문(회)'),
    cell({ text: num(r.uncollectedDemandKg, 1, 'kg'), attr: warn(r.uncollectedDemandKg) }, '용량부족 미수거'),
    cell({ text: num(r.trafficPenalty, 2), attr: warn(r.trafficPenalty) }, '교통 패널티'),
    cell({ text: '±' + num(r.stdComplaints, 1) }, '표준편차'),
    cell({ text: (cfg?.days ?? 30) + '일', attr: meta }, '시뮬레이션 기간'),
    cell({ text: String(cfg?.seeds ?? 30), attr: meta }, '반복 시드')
  ].join('');
  bubble.appendChild(stats);

  // 직업별 바
  const occSummary = r.byOccupationSummary;
  if (occSummary) {
    const maxVal = Math.max(...Object.values(occSummary));
    const labels = { BlueCollar: '생산직', Student: '학생', Housewife: '전업주부' };
    // 직업군은 서로 다른 "값"이지 좋고 나쁨이 아니다 — 안전색(액센트)을 여기 뿌리면
    // 화면에서 주목해야 할 자리가 흐려진다. 그래서 명도만 다른 중성 계열로 구분한다.
    const colors = {
      BlueCollar: CSSa('--text', 0.90),
      Student:    CSSa('--text', 0.62),
      Housewife:  CSSa('--text', 0.36)
    };

    const barsDiv = document.createElement('div');
    barsDiv.className = 'occ-bars';
    barsDiv.style.marginTop = '10px';

    for (const [key, val] of Object.entries(occSummary)) {
      const pct = maxVal > 0 ? (val / maxVal * 100).toFixed(1) : 0;
      barsDiv.innerHTML += `
        <div class="occ-row">
          <div class="occ-label">${labels[key] || key}</div>
          <div class="occ-bar-wrap">
            <div class="occ-bar" style="width:${pct}%;background:${colors[key] || CSS('--muted')}"></div>
          </div>
          <div class="occ-val">${Number(val).toFixed(1)}</div>
        </div>`;
    }
    bubble.appendChild(barsDiv);
  }

  // 트럭별 운행 롤업 (§3.4) — 병목 트럭 식별
  if (r.tripMetrics && r.tripMetrics.length > 0) {
    const byTruck = {};
    for (const t of r.tripMetrics) {
      const a = byTruck[t.truckId] || { trips: 0, collected: 0, partial: 0 };
      a.trips += 1;
      a.collected += t.collectedKg || 0;
      if ((t.partialPickupCount || 0) > 0) a.partial += 1;
      byTruck[t.truckId] = a;
    }
    // 표 스타일은 .data-tbl 하나로 모았다 — 예전엔 style="" 인라인으로 흩어져 있어
    // 엣지 쪽 표와 생김새가 달랐고, 팔레트를 바꿔도 여기만 옛 색으로 남았다.
    // 부분수거는 실제로 발생했을 때만 경고색을 입힌다(위 통계 칸과 같은 규칙).
    const rows = Object.entries(byTruck).map(([id, a]) =>
      `<tr><td>${id}</td><td>${a.trips}</td>` +
      `<td>${a.collected.toFixed(1)}kg</td>` +
      `<td${a.partial > 0 ? ' style="color:var(--warn)"' : ''}>${a.partial || '—'}</td></tr>`).join('');
    const tw = document.createElement('div');
    tw.className = 'data-block';
    tw.innerHTML = `<div class="data-block-title">트럭별 운행</div>
      <table class="data-tbl">
        <thead><tr>
          <th>트럭</th><th>운행</th><th>수거</th><th>부분수거</th>
        </tr></thead><tbody>${rows}</tbody></table>`;
    bubble.appendChild(tw);
  }

  // 잔류량 분포 (§3.5) — 유형별 잔류 + 최대 잔류 건물
  if (r.residualByWasteType && Object.keys(r.residualByWasteType).length > 0) {
    const typeLabel = { GENERAL: '일반', FOOD: '음식물', RECYCLING: '재활용' };
    const parts = Object.entries(r.residualByWasteType)
      .map(([k, v]) => `${typeLabel[k] || k} ${Number(v).toFixed(1)}kg`).join(' · ');
    const rd = document.createElement('div');
    rd.className = 'data-block';
    let html = `<div class="data-block-title">유형별 잔류</div><div>${parts}</div>`;
    if (r.maxResidualBuilding && r.maxResidualBuildingKg > 0) {
      html += `<div style="margin-top:5px;color:var(--warn)">최대 잔류 건물: ${r.maxResidualBuilding} ${Number(r.maxResidualBuildingKg).toFixed(1)}kg</div>`;
    }
    rd.innerHTML = html;
    bubble.appendChild(rd);
  }

  // 히스토그램
  if (r.allTotals && r.allTotals.length > 0) {
    const cWrap = document.createElement('div');
    // x축에 시드 수만큼(기본 30개) 라벨이 다 들어가야 해서, 가로 라벨일 때보다
    // 세로 공간이 더 필요하다 — autoSkip:false로 라벨을 전부 표시하면서
    // 겹치지 않게 하려면 90도 회전이 필요하고, 그 회전된 라벨이 들어갈 자리를
    // 확보하려고 높이를 160→200px로 늘렸다. 실측(라이브 브라우저 테스트)으로
    // 기존엔 Chart.js가 홀수 번째(S1,S3,S5...) 라벨만 자동으로 남기고 나머지를
    // 생략해, 사용자가 "데이터가 잘려서 안 보인다"고 오해하는 걸 확인했다
    // (실제로는 막대 30개 모두 그려져 있었고 라벨만 절반이 생략된 것이었음).
    cWrap.style.cssText = 'position:relative;height:200px;width:100%;margin-top:12px';
    const c = document.createElement('canvas');
    c.style.cssText = 'max-height:200px';
    cWrap.appendChild(c);
    bubble.appendChild(cWrap);

    setTimeout(() => {
      // 막대는 무채색으로 두고 최댓값(=민원이 가장 많이 난 시드)만 경고색으로 짚는다.
      // 이 히스토그램에서 읽어야 할 건 "평균이 얼마인가"가 아니라 "최악이 어디까지
      // 갔는가"라서, 그 막대 하나만 색이 있으면 눈이 바로 거기로 간다. 전부 칠하면
      // 색이 정보를 더하지 않고 배경만 된다.
      //
      // 동점이면 여러 막대가 함께 칠해진다 — 그게 사실이므로 임의로 하나만 고르지 않는다.
      const maxTotal = Math.max(...r.allTotals);
      const isPeak = v => v === maxTotal;

      new Chart(c, {
        type: 'bar',
        data: {
          labels: r.allTotals.map((_, i) => 'S' + (i + 1)),
          datasets: [{
            label: '민원 수',
            data: r.allTotals,
            backgroundColor: r.allTotals.map(v =>
                isPeak(v) ? CSSa('--warn', 0.85) : CSSa('--muted', 0.55)),
            borderColor: r.allTotals.map(v =>
                isPeak(v) ? CSS('--warn') : CSS('--muted')),
            borderWidth: 1
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: { display: false },
            tooltip: { callbacks: { label: ctx => ctx.parsed.y + '건' } }
          },
          scales: {
            x: {
              ticks: { color: CSS('--muted'), font: { size: 10 }, autoSkip: false, maxRotation: 90, minRotation: 90 },
              grid: { display: false }
            },
            y: { ticks: { color: CSS('--muted'), font: { size: 10 } }, grid: { color: CSS('--border') } }
          }
        }
      });
    }, 100);
  }

  div.appendChild(bubble);
  return div;
}

function updateSidePanel(msg) {
  const r = msg.simulationResult;
  if (!r) return;
  document.getElementById('statMean').textContent = r.meanComplaints?.toFixed(1) ?? '—';
  document.getElementById('statStd').textContent = r.stdComplaints?.toFixed(1) ?? '—';
  document.getElementById('resultPanel').classList.add('show');

  if (r.allTotals) {
    const ctx = document.getElementById('sideChart').getContext('2d');
    if (sideChart) sideChart.destroy();
    sideChart = new Chart(ctx, {
      type: 'line',
      data: {
        labels: r.allTotals.map((_, i) => i + 1),
        // 결과 카드의 히스토그램과 같은 데이터라 짚는 지점도 같아야 한다 —
        // 한쪽에서만 최댓값을 표시하면 두 그림이 서로 다른 얘기를 하는 것처럼 보인다.
        datasets: [{
          data: r.allTotals,
          borderColor: CSS('--muted'),
          backgroundColor: CSSa('--muted', 0.14),
          tension: 0.4,
          pointRadius: r.allTotals.map(v => (v === Math.max(...r.allTotals) ? 4 : 2)),
          pointBackgroundColor: r.allTotals.map(v =>
              v === Math.max(...r.allTotals) ? CSS('--warn') : CSS('--muted')),
          pointBorderColor: r.allTotals.map(v =>
              v === Math.max(...r.allTotals) ? CSS('--warn') : CSS('--muted')),
          fill: true
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: {
          x: { display: false },
          y: { ticks: { color: CSS('--muted'), font: { size: 10 } }, grid: { color: CSSa('--border', 0.6) } }
        }
      }
    });
  }
}

/*
 * 24시간 띠의 커서를 지금 고른 수거 시각에 맞춘다.
 *
 * 값이 "HH:MM"으로 파싱되지 않으면(직접 입력 도중의 미완성 문자열 등) 아무것도
 * 하지 않는다 — 입력 중간 상태마다 커서가 0시로 튀면 오히려 읽기 어렵다.
 */
function syncHourBand() {
  const band = document.getElementById('hourBand');
  if (!band) return;
  const sel = document.getElementById('pTime');
  if (!sel) return;
  const raw = sel.value === 'custom'
    ? (document.getElementById('pTimeCustom')?.value ?? '')
    : sel.value;
  const m = /^\s*([01]?\d|2[0-3]):([0-5]\d)\s*$/.exec(raw);
  if (!m) return;
  const minutes = Number(m[1]) * 60 + Number(m[2]);
  band.style.setProperty('--hour-pos', (minutes / 1440).toFixed(4));
}

// ── 빠른 실행 (사이드바) ─────────────────────────────────────────
function getQuickConfig() {
  const tSel = document.getElementById('pTime').value;
  const time = tSel === 'custom' ? document.getElementById('pTimeCustom').value : tSel;
  return {
    collectionTimeLabel: time,
    days: parseInt(document.getElementById('pDays').value),
    seeds: parseInt(document.getElementById('pSeeds').value),
    leaveSigma: parseFloat(document.getElementById('pLeaveSigma').value),
    wasteSigma: 0.3,
    threshold: 0.8,
    capacity: 30.0,
    numBuildings: 4,
    residentsPerBuilding: 25
  };
}

function quickRun() {
  const cfg = getQuickConfig();
  const msg = `수거 시각 ${cfg.collectionTimeLabel}, ${cfg.days}일, ${cfg.seeds}시드로 시뮬레이션을 실행해줘. 외출 분산은 ${cfg.leaveSigma}분이야.`;
  document.getElementById('msgInput').value = msg;
  send();
}

// 채팅 파이프라인은 collectionTime을 1개만 추출/실행할 수 있어 자연어로
// "여러 시각 비교"를 보내면 실행되지 않는다(의도분류 규칙상 시각 2개 이상은
// 순간값 조회로 간주해 거부). 그래서 이 버튼은 채팅이 아니라 이미 검증된
// /api/scenario/collection-sweep을 10:00~14:00·2시간 간격으로 직접 호출해
// 진짜 3지점 비교를 실행한다(사이드바 "시나리오 실험" 버튼들과 동일 패턴).
async function compareRun() {
  const cfg = getQuickConfig();
  appendLocalMessage('system', ' [수거시각 비교] 10:00·12:00·14:00 비교 실행 중... (시드 ' + cfg.seeds + '회, ' + cfg.days + '일)');
  try {
    const res = await fetch('/api/scenario/collection-sweep', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        days: cfg.days, seeds: cfg.seeds, leaveSigma: cfg.leaveSigma,
        start: '10:00', end: '14:00', stepMinutes: 120
      })
    });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    renderScenario(data, 'line');
  } catch (e) {
    appendLocalMessage('system', ' 비교 실행 실패: ' + e.message);
  }
}

// ── 교통량 확인 ──────────────────────────────────────────────────
const TRAFFIC_NODE_LABELS = {
  Node_A: '장성초등학교(A)', Node_B: '양덕(B)',
  Node_C: '장성초등사거리(C)', Node_D: '두산위브·포항온천(D)'
};

async function showTraffic() {
  appendLocalMessage('system', ' 교통량 데이터를 불러오는 중...');
  try {
    const res = await fetch('/api/traffic/default');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    renderTraffic(data);
  } catch (e) {
    appendLocalMessage('system', ' 교통량 조회 실패: ' + e.message);
  }
}

function renderTraffic(p) {
  const container = document.getElementById('messages');
  const div = document.createElement('div');
  div.className = 'msg result';
  const bubble = document.createElement('div');
  bubble.className = 'bubble';

  const header = document.createElement('div');
  header.className = 'result-header';
  header.textContent = ` 시간대별 교통 혼잡도 — ${p.id}`;
  bubble.appendChild(header);

  const note = document.createElement('div');
  note.style.cssText = 'font-size:12px;color:var(--muted);margin-bottom:8px';
  note.textContent = `혼잡 가중치가 ${p.congestionThresholdRed} 이상이면 정체(RED)로 판정되어 트럭 이동시간과 별도 교통 패널티가 증가합니다. 생활쓰레기 민원에는 합산하지 않습니다. 실측 포항 교통량 기반 데이터입니다.`;
  bubble.appendChild(note);

  const cWrap = document.createElement('div');
  cWrap.style.cssText = 'position:relative;height:260px;width:100%;margin:6px 0 12px';
  const canvas = document.createElement('canvas');
  canvas.style.cssText = 'max-height:260px';
  cWrap.appendChild(canvas);
  bubble.appendChild(cWrap);

  div.appendChild(bubble);
  container.appendChild(div);
  container.scrollTop = container.scrollHeight;

  setTimeout(() => drawTrafficChart(canvas, p), 80);
}

function drawTrafficChart(canvas, p) {
  const labels = Array.from({ length: 24 }, (_, h) => h + '시');
  const datasets = [{
    label: '전역 평균',
    data: p.hourlyWeight,
    borderColor: CSS('--muted'),
    backgroundColor: CSSa('--muted', 0.14),
    borderWidth: 3,
    tension: 0.3,
    pointRadius: 2,
    fill: true
  }];
  if (p.nodeHourlyWeight) {
    Object.entries(p.nodeHourlyWeight).forEach(([node, arr], i) => {
      datasets.push({
        label: TRAFFIC_NODE_LABELS[node] || node,
        data: arr,
        borderColor: SCN_COLORS[(i + 1) % SCN_COLORS.length],
        borderWidth: 1.5,
        tension: 0.3,
        pointRadius: 0,
        fill: false
      });
    });
  }

  new Chart(canvas, {
    type: 'line',
    data: { labels, datasets },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: true, labels: { color: CSS('--text'), font: { size: 11 }, boxWidth: 14 } },
        tooltip: { callbacks: { label: ctx => `${ctx.dataset.label}: ${ctx.parsed.y}` } }
      },
      scales: {
        x: {
          title: { display: true, text: '시각', color: CSS('--muted'), font: { size: 11 } },
          ticks: { color: CSS('--muted'), font: { size: 10 } },
          grid: { display: false }
        },
        y: {
          title: { display: true, text: '혼잡 가중치', color: CSS('--muted'), font: { size: 11 } },
          ticks: { color: CSS('--muted'), font: { size: 10 } },
          grid: { color: CSSa('--border', 0.6) },
          beginAtZero: true
        }
      }
    }
  });
}

// ── 시나리오 실험 ─────────────────────────────────────────────────
const SCENARIO_META = {
  'occupation-mix':  { title: '거주민 구성별 최적 수거시각', chart: 'line' },
  'collection-sweep':{ title: '수거시각 sweep (06~18시)',   chart: 'line' },
  'behavior-grid':   { title: '행동 변동 α×β 민감도',        chart: 'line' },
  'infra-grid':      { title: '인프라 용량×임계 트레이드오프', chart: 'line' },
  'density':         { title: '밀도: 빌라촌 vs 원룸촌',       chart: 'bar'  },
  'collection-schedule': { title: '수거 스케줄별 민원',        chart: 'bar'  },
  'multi-truck':     { title: '다중 트럭·구역 분할',          chart: 'bar'  },
  'waste-separation':{ title: '분리배출 효과',                chart: 'bar'  },
  'new-occupations': { title: '확장 거주민 유형별 최적 수거시각', chart: 'line' },
  'coupling-variants':{ title: '결합모델 변형(귀가·임대인)',   chart: 'bar'  },
  'monthly-waste':   { title: '월별 배출량(1년·최다 달)',      chart: 'bar'  },
  'truck-route':     { title: '차종 × 방문 순서 탐색',         chart: 'bar'  }
};
// 계열 구분용 범주색 — 상황색과 같은 세계(현장·산업)에서 고르되, 명도가 겁치지
// 않게 배열해 흑백으로 가도 구분된다. 첫 계열에만 안전색을 둔다 — 보통 그게 기준선이다.
const SCN_COLORS = ['#7f9db8', '#c98a44', '#9aa0a8', '#b8635c', '#7fa05a', '#5f7d8c'];

async function runScenario(type) {
  const meta = SCENARIO_META[type];
  // 공통 base 설정은 사이드바 값에서 가져오기 (days/seeds 등)
  const days = parseInt(document.getElementById('pDays').value) || 30;
  const seeds = Math.min(parseInt(document.getElementById('pSeeds').value) || 10, 15);
  const leaveSigma = parseFloat(document.getElementById('pLeaveSigma').value) || 30;

  // 버튼 로딩 표시
  const btns = document.querySelectorAll('.scenario-btn');
  btns.forEach(b => b.disabled = true);
  appendLocalMessage('system', ` [${meta.title}] 시나리오 실행 중... (시드 ${seeds}회, ${days}일)`);

  try {
    const res = await fetch('/api/scenario/' + type, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ days, seeds, leaveSigma })
    });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    renderScenario(data, meta.chart);
  } catch (e) {
    appendLocalMessage('system', ' 시나리오 실행 실패: ' + e.message);
  } finally {
    btns.forEach(b => b.disabled = false);
  }
}

function renderScenario(data, chartType) {
  const container = document.getElementById('messages');
  const div = document.createElement('div');
  div.className = 'msg result';
  const bubble = document.createElement('div');
  bubble.className = 'bubble';

  // 헤더
  const header = document.createElement('div');
  header.className = 'result-header';
  header.textContent = ' ' + (data.title || '시나리오 결과');
  bubble.appendChild(header);

  // 차트 (Chart.js maintainAspectRatio:false → 부모에 '명시적' 높이 필요)
  const cWrap = document.createElement('div');
  cWrap.style.cssText = 'position:relative;height:260px;width:100%;margin:6px 0 12px';
  const canvas = document.createElement('canvas');
  canvas.style.cssText = 'max-height:260px';   // 전역 canvas{max-height:160px} 무력화
  cWrap.appendChild(canvas);
  bubble.appendChild(cWrap);

  // 인사이트
  if (data.insights && data.insights.length) {
    const ins = document.createElement('div');
    ins.className = 'scn-insights';
    data.insights.forEach(it => {
      const row = document.createElement('div');
      row.className = 'scn-insight';
      // occupation-mix 형식: {scenario, desc, ratio, bestTime, bestMean}
      if (it.scenario) {
        row.innerHTML =
          `<span class="k">${it.scenario}</span>` +
          `<span class="v">${it.desc} · 최적 <span class="best">${it.bestTime}</span> ` +
          `(${it.bestMean}건)</span>`;
      } else {
        row.innerHTML =
          `<span class="k">${it.key}</span><span class="v">${formatVal(it.value)}</span>`;
      }
      ins.appendChild(row);
    });
    bubble.appendChild(ins);
  }

  div.appendChild(bubble);
  container.appendChild(div);
  container.scrollTop = container.scrollHeight;

  // Chart.js 렌더 (DOM 부착 후)
  setTimeout(() => drawScenarioChart(canvas, data, chartType), 80);
}

function formatVal(v) {
  if (v && typeof v === 'object') return JSON.stringify(v);
  return v;
}

function drawScenarioChart(canvas, data, chartType) {
  const datasets = (data.series || []).map((s, i) => ({
    label: s.name,
    data: s.values,
    borderColor: SCN_COLORS[i % SCN_COLORS.length],
    backgroundColor: chartType === 'bar'
      ? SCN_COLORS[i % SCN_COLORS.length] + 'cc'
      : SCN_COLORS[i % SCN_COLORS.length] + '20',
    borderWidth: 2,
    tension: 0.35,
    pointRadius: 3,
    fill: chartType === 'line' && (data.series || []).length === 1
  }));

  new Chart(canvas, {
    type: chartType,
    data: { labels: data.xCategories, datasets },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: {
          display: datasets.length > 1,
          labels: { color: CSS('--text'), font: { size: 11 }, boxWidth: 14 }
        },
        tooltip: { callbacks: { label: ctx => `${ctx.dataset.label}: ${ctx.parsed.y}${data.yUnit || '건'}` } }
      },
      scales: {
        x: {
          title: { display: true, text: data.xLabel, color: CSS('--muted'), font: { size: 11 } },
          ticks: { color: CSS('--muted'), font: { size: 10 } },
          grid: { display: false }
        },
        y: {
          title: { display: true, text: data.yLabel, color: CSS('--muted'), font: { size: 11 } },
          ticks: { color: CSS('--muted'), font: { size: 10 } },
          grid: { color: CSSa('--border', 0.6) },
          beginAtZero: true
        }
      }
    }
  });
}

// ── 도메인 등록 ───────────────────────────────────────────────────
Domains.register({
  id: 'waste',
  icon: 'WASTE',
  label: '장량동 생활쓰레기 수거',
  tagline: '수거 시각·트럭 편성·교통 정체를 바꿔가며 민원 발생을 예측합니다.',
  title: '장량동 생활쓰레기 시뮬레이션',
  placeholder: '시뮬레이션 조건을 자연어로 입력하세요... (Shift+Enter: 줄바꿈, Enter: 전송)',
  greeting:
    '장량동 생활쓰레기 시뮬레이션입니다.\n\n' +
    '수거 시각·거주민 특성이 민원 발생에 어떤 영향을 미치는지 DEVS(이산사건시스템) 모델로 분석합니다.\n\n' +
    '아래 예시 질문을 눌러보거나, 직접 원하는 조건을 입력해주세요.',
  chips: [
    { label: '12시 수거 실행', text: '수거 시각을 12시로 설정하고 시뮬레이션 실행해줘' },
    { label: '수거시각 비교', run: () => compareRun() },
    { label: '분산 σ=90 실험', text: '외출 시각 분산이 σ=90분일 때 12시 수거로 실행해줘' },
    { label: '모델 설명', text: '이 시뮬레이션 모델에 대해 설명해줘' },
    { label: '직업별 차이', text: '직업별로 민원이 왜 다른지 설명해줘' }
  ],

  // 사이드바가 DOM에 붙은 직후 호출된다. 이전에는 <script> 최상단에서 바로
  // 등록하던 리스너인데, 사이드바가 동적으로 마운트되면서 여기로 옮겼다.
  init() {
    const sel = document.getElementById('pTime');
    const custom = document.getElementById('pTimeCustom');
    if (sel) {
      sel.addEventListener('change', function () {
        document.getElementById('customTimeRow').style.display =
          this.value === 'custom' ? 'flex' : 'none';
        syncHourBand();
      });
    }
    if (custom) custom.addEventListener('input', syncHourBand);
    syncHourBand();
  },

  /** 장량동 고유 메시지 타입만 가로챈다. 처리하지 않으면 false → chat.js 기본 렌더러. */
  renderMessage(msg) {
    if (msg.type === 'RESULT') {
      document.getElementById('messages').appendChild(buildResultBubble(msg));
      updateSidePanel(msg);
      return true;
    }
    if (msg.type === 'SCENARIO') {
      // 사이드바 "시나리오 실험" 버튼과 동일한 렌더러 재사용(ScenarioIntentDetector로
      // 채팅에서도 자연어로 트리거된 결과).
      const chartType = (SCENARIO_META[msg.scenarioType] && SCENARIO_META[msg.scenarioType].chart) || 'line';
      renderScenario(msg.scenarioResponse, chartType);
      return true;
    }
    return false;
  }
});
