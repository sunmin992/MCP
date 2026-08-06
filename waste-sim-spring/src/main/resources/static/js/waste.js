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
  const stats = document.createElement('div');
  stats.className = 'result-stats';
  stats.innerHTML = `
    <div class="result-stat">
      <div class="v">${r.meanComplaints?.toFixed(1) ?? '—'}</div>
      <div class="l">평균 생활 민원</div>
    </div>
    <div class="result-stat">
      <div class="v">${r.meanWasteOverflowComplaints?.toFixed(1) ?? '—'}</div>
      <div class="l">적재 초과 민원</div>
    </div>
    <div class="result-stat">
      <div class="v">${r.meanLandlordComplaints?.toFixed(1) ?? '—'}</div>
      <div class="l">임대인 민원</div>
    </div>
    <div class="result-stat">
      <div class="v" style="color:var(--yellow)">${r.trafficPenalty?.toFixed(2) ?? '—'}</div>
      <div class="l">교통 패널티</div>
    </div>
    <div class="result-stat">
      <div class="v" style="color:var(--yellow)">±${r.stdComplaints?.toFixed(1) ?? '—'}</div>
      <div class="l">표준편차</div>
    </div>
    <div class="result-stat">
      <div class="v" style="color:var(--muted)">${cfg?.days ?? 30}일</div>
      <div class="l">시뮬레이션 기간</div>
    </div>
    <div class="result-stat">
      <div class="v" style="color:var(--muted)">${cfg?.seeds ?? 30}</div>
      <div class="l">반복 시드</div>
    </div>`;
  bubble.appendChild(stats);

  // 직업별 바
  const occSummary = r.byOccupationSummary;
  if (occSummary) {
    const maxVal = Math.max(...Object.values(occSummary));
    const labels = { BlueCollar: '생산직', Student: '학생', Housewife: '전업주부' };
    const colors = { BlueCollar: '#5c73f2', Student: '#4ade80', Housewife: '#f87171' };

    const barsDiv = document.createElement('div');
    barsDiv.className = 'occ-bars';
    barsDiv.style.marginTop = '10px';

    for (const [key, val] of Object.entries(occSummary)) {
      const pct = maxVal > 0 ? (val / maxVal * 100).toFixed(1) : 0;
      barsDiv.innerHTML += `
        <div class="occ-row">
          <div class="occ-label">${labels[key] || key}</div>
          <div class="occ-bar-wrap">
            <div class="occ-bar" style="width:${pct}%;background:${colors[key] || '#5c73f2'}"></div>
          </div>
          <div class="occ-val">${Number(val).toFixed(1)}</div>
        </div>`;
    }
    bubble.appendChild(barsDiv);
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
      new Chart(c, {
        type: 'bar',
        data: {
          labels: r.allTotals.map((_, i) => 'S' + (i + 1)),
          datasets: [{
            label: '민원 수',
            data: r.allTotals,
            backgroundColor: '#5c73f290',
            borderColor: '#5c73f2',
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
              ticks: { color: '#8892aa', font: { size: 9 }, autoSkip: false, maxRotation: 90, minRotation: 90 },
              grid: { display: false }
            },
            y: { ticks: { color: '#8892aa', font: { size: 10 } }, grid: { color: '#2d3250' } }
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
        datasets: [{
          data: r.allTotals,
          borderColor: '#5c73f2',
          backgroundColor: '#5c73f215',
          tension: 0.4,
          pointRadius: 2,
          fill: true
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: {
          x: { display: false },
          y: { ticks: { color: '#8892aa', font: { size: 10 } }, grid: { color: '#2d325060' } }
        }
      }
    });
  }
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
    borderColor: '#5c73f2',
    backgroundColor: '#5c73f220',
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
        legend: { display: true, labels: { color: '#e2e8f0', font: { size: 11 }, boxWidth: 14 } },
        tooltip: { callbacks: { label: ctx => `${ctx.dataset.label}: ${ctx.parsed.y}` } }
      },
      scales: {
        x: {
          title: { display: true, text: '시각', color: '#8892aa', font: { size: 11 } },
          ticks: { color: '#8892aa', font: { size: 9 } },
          grid: { display: false }
        },
        y: {
          title: { display: true, text: '혼잡 가중치', color: '#8892aa', font: { size: 11 } },
          ticks: { color: '#8892aa', font: { size: 10 } },
          grid: { color: '#2d325060' },
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
  'monthly-waste':   { title: '월별 배출량(1년·최다 달)',      chart: 'bar'  }
};
const SCN_COLORS = ['#5c73f2', '#4ade80', '#f87171', '#facc15', '#7c5cf2', '#38bdf8'];

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
          labels: { color: '#e2e8f0', font: { size: 11 }, boxWidth: 14 }
        },
        tooltip: { callbacks: { label: ctx => `${ctx.dataset.label}: ${ctx.parsed.y}${data.yUnit || '건'}` } }
      },
      scales: {
        x: {
          title: { display: true, text: data.xLabel, color: '#8892aa', font: { size: 11 } },
          ticks: { color: '#8892aa', font: { size: 10 } },
          grid: { display: false }
        },
        y: {
          title: { display: true, text: data.yLabel, color: '#8892aa', font: { size: 11 } },
          ticks: { color: '#8892aa', font: { size: 10 } },
          grid: { color: '#2d325060' },
          beginAtZero: true
        }
      }
    }
  });
}

// ── 도메인 등록 ───────────────────────────────────────────────────
Domains.register({
  id: 'waste',
  icon: '🗑️',
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
    if (sel) {
      sel.addEventListener('change', function () {
        document.getElementById('customTimeRow').style.display =
          this.value === 'custom' ? 'flex' : 'none';
      });
    }
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
