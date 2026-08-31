/*
 * 장량동 생활쓰레기 수거 도메인 — 도메인 고유 메시지 렌더링.
 *
 * RESULT/SCENARIO/SUBTASK/PREVIEW 처리를 renderMessage()로 노출해 chat.js가 도메인
 * 이름을 몰라도 위임할 수 있게 한다.
 *
 * <실행 입구는 채팅 하나다>
 * 사이드바의 빠른 실행·시나리오 실험 버튼과 예시 질문 칩은 제거했다. 그 버튼들은
 * REST(/api/simulate, /api/scenario/*)를 직접 불러 채팅 게이트와 v1.13 수집 계층을
 * <b>둘 다</b> 건너뛰었다 — 수집 도중에 눌리면 세션은 답을 기다리는 채로 남고 화면에는
 * 엉뚱한 결과가 그려진다. 실행 입구가 하나여야 "준비되지 않은 실행은 엔진에 도달하지
 * 않는다"(D-52)가 화면에서도 성립한다.
 *
 * REST 엔드포인트 자체는 그대로 살아 있다 — MCP 클라이언트와 스크립트가 쓰는 계약이고,
 * 없앤 것은 그것을 부르던 화면의 버튼이다.
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
      <div class="v">${r.collectedWasteKg?.toFixed(1) ?? '—'}kg</div>
      <div class="l">평균 수거량</div>
    </div>
    <div class="result-stat">
      <div class="v" style="color:var(--yellow)">${r.residualWasteKg?.toFixed(1) ?? '—'}kg</div>
      <div class="l">평균 잔류량</div>
    </div>
    <div class="result-stat">
      <div class="v">${r.truckUtilizationPercent?.toFixed(1) ?? '—'}%</div>
      <div class="l">트럭 이용률</div>
    </div>
    <div class="result-stat">
      <div class="v" style="color:var(--yellow)">${r.unservedPickupCount ?? '—'}</div>
      <div class="l">미수거 방문(회)</div>
    </div>
    <div class="result-stat">
      <div class="v" style="color:var(--yellow)">${r.uncollectedDemandKg?.toFixed(1) ?? '—'}kg</div>
      <div class="l">용량부족 미수거</div>
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
    const rows = Object.entries(byTruck).map(([id, a]) =>
      `<tr><td>${id}</td><td style="text-align:right">${a.trips}</td>` +
      `<td style="text-align:right">${a.collected.toFixed(1)}kg</td>` +
      `<td style="text-align:right;color:var(--yellow)">${a.partial || '—'}</td></tr>`).join('');
    const tw = document.createElement('div');
    tw.style.cssText = 'margin-top:12px;font-size:13px';
    tw.innerHTML = `<div style="color:var(--muted);margin-bottom:4px">트럭별 운행</div>
      <table style="width:100%;border-collapse:collapse">
        <thead><tr style="color:var(--muted)">
          <th style="text-align:left">트럭</th><th style="text-align:right">운행</th>
          <th style="text-align:right">수거</th><th style="text-align:right">부분수거</th>
        </tr></thead><tbody>${rows}</tbody></table>`;
    bubble.appendChild(tw);
  }

  // 잔류량 분포 (§3.5) — 유형별 잔류 + 최대 잔류 건물
  if (r.residualByWasteType && Object.keys(r.residualByWasteType).length > 0) {
    const typeLabel = { GENERAL: '일반', FOOD: '음식물', RECYCLING: '재활용' };
    const parts = Object.entries(r.residualByWasteType)
      .map(([k, v]) => `${typeLabel[k] || k} ${Number(v).toFixed(1)}kg`).join(' · ');
    const rd = document.createElement('div');
    rd.style.cssText = 'margin-top:12px;font-size:13px';
    let html = `<div style="color:var(--muted);margin-bottom:4px">유형별 잔류</div><div>${parts}</div>`;
    if (r.maxResidualBuilding && r.maxResidualBuildingKg > 0) {
      html += `<div style="margin-top:4px;color:var(--yellow)">최대 잔류 건물: ${r.maxResidualBuilding} ${Number(r.maxResidualBuildingKg).toFixed(1)}kg</div>`;
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
// ── 시나리오 실험 ─────────────────────────────────────────────────
const SCENARIO_META = {
  'occupation-mix':  { title: '거주민 구성별 최적 수거시각', chart: 'line' },
  'collection-sweep':{ title: '수거시각 sweep (06~18시)',   chart: 'line' },
  'collection-time-comparison':{ title: '지정 수거 시각 비교', chart: 'bar' },
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
const SCN_COLORS = ['#5c73f2', '#4ade80', '#f87171', '#facc15', '#7c5cf2', '#38bdf8'];

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
/* ── v1.13 고정 서브태스크 수집 패널 (SDD 2.18.10) ─────────────────────────
 *
 * 질문을 텍스트 버블로만 찍지 않는 이유는, 자료형에 맞는 입력 위젯을 띄워야 하기
 * 때문이다 — 시각은 시간 입력, 차종·엔진은 선택지, 경로는 목록 입력이다. 서버가
 * inputSchema를 함께 내려보내므로 클라이언트가 문구를 파싱해 추측하지 않아도 된다.
 *
 * 위젯이 만드는 값도 결국 같은 /app/chat.send로 간다 — 별도 제출 경로를 만들면
 * 그 경로만 서버의 정규화·검증 순서를 건너뛸 수 있다.
 */

/**
 * 서버가 준 inputSchema로 이 질문에 맞는 입력 요소를 만든다.
 *
 * <p>자료형이 위젯을 정한다 — 허용 값 목록이 있다고 무조건 드롭다운을 띄우면 안 된다.
 * 비율 맵(NUMBER_MAP)과 다중 선택(ENUM_LIST)도 허용 값 목록을 갖는데, 그 둘에 단일
 * 선택 드롭다운을 씌우면 사용자가 답을 <b>넣을 수가 없다</b>(실측으로 막힌 지점이다).
 */
function buildSubtaskInput(schema) {
  const type = schema.answerType;
  const range = schema.allowedRange || {};
  const values = Array.isArray(range.values) ? range.values : [];

  // 단일 선택 — 값이 정확히 하나인 항목만.
  if (type === 'ENUM' && values.length > 0) {
    const sel = document.createElement('select');
    sel.className = 'subtask-input';
    const blank = document.createElement('option');
    blank.value = '';
    blank.textContent = '선택하세요';
    sel.appendChild(blank);
    values.forEach((v) => {
      const opt = document.createElement('option');
      opt.value = v;
      opt.textContent = v;
      sel.appendChild(opt);
    });
    return sel;
  }

  // 다중 선택 — 여러 개를 고르는 항목. 고른 값을 쉼표로 이어 보낸다.
  if (type === 'ENUM_LIST' && values.length > 0) {
    const sel = document.createElement('select');
    sel.className = 'subtask-input subtask-multi';
    sel.multiple = true;
    sel.size = Math.min(values.length, 5);
    values.forEach((v) => {
      const opt = document.createElement('option');
      opt.value = v;
      opt.textContent = v;
      sel.appendChild(opt);
    });
    // .value는 다중 선택에서 첫 값만 준다 — 전부 모아서 돌려주도록 덮어쓴다.
    Object.defineProperty(sel, 'value', {
      get() {
        return Array.from(this.selectedOptions).map((o) => o.value).join(', ');
      },
      set(v) {
        const want = String(v).split(/\s*,\s*/);
        Array.from(this.options).forEach((o) => { o.selected = want.includes(o.value); });
      }
    });
    return sel;
  }

  const input = document.createElement('input');
  input.className = 'subtask-input';
  if (type === 'TIME') {
    input.type = 'time';
  } else if (type === 'INTEGER' || type === 'NUMBER') {
    input.type = 'number';
    if (range.min !== undefined) input.min = range.min;
    if (range.max !== undefined) input.max = range.max;
    if (type === 'NUMBER') input.step = 'any';
  } else {
    input.type = 'text';
    if (type === 'INTEGER_MAP' || type === 'NUMBER_MAP') {
      input.placeholder = values.length
        ? `${values[0]}=0.5, ${values[1] || '항목'}=0.5`
        : '항목=값, 항목=값';
    } else if (type === 'TIME_LIST') {
      input.placeholder = '09:00, 18:00 (없으면 해당 없음)';
    } else if (type === 'STRING_LIST') {
      input.placeholder = '쉼표로 구분해 입력 (예: Node_A, Node_B)';
    } else {
      input.placeholder = range.description || '';
    }
  }
  return input;
}

function buildSubtaskBubble(msg) {
  const div = document.createElement('div');
  div.className = 'msg subtask';
  // ST 번호는 화면에 <b>글자로는</b> 나오지 않지만, 답변을 되돌려 보낼 때와 디버깅에는
  // 필요하다. data 속성에 두면 사용자에게는 보이지 않고 도구는 찾을 수 있다.
  div.dataset.stid = msg.currentSubtaskId || '';

  const bubble = document.createElement('div');
  bubble.className = 'bubble subtask-bubble';

  // 사용자에게는 ST 번호를 보이지 않는다 — 보이는 것은 "3/8 · 단계 이름"과 그 안에서의
  // "질문 2"뿐이다. currentSubtaskId는 답변을 되돌려 보낼 때만 쓰는 내부 식별자다.
  const head = document.createElement('div');
  head.className = 'subtask-head';
  head.textContent = `현재 단계 ${msg.groupOrder} / ${msg.groupTotal}`;
  bubble.appendChild(head);

  const stepName = document.createElement('div');
  stepName.className = 'subtask-step';
  stepName.textContent = msg.groupName || '';
  bubble.appendChild(stepName);

  if (msg.groupDescription) {
    const stepDesc = document.createElement('div');
    stepDesc.className = 'subtask-step-desc';
    stepDesc.textContent = msg.groupDescription;
    bubble.appendChild(stepDesc);
  }

  const bar = document.createElement('div');
  bar.className = 'subtask-progress';
  const fill = document.createElement('div');
  fill.className = 'subtask-progress-fill';
  fill.style.width = Math.round((msg.progress || 0) * 100) + '%';
  bar.appendChild(fill);
  bubble.appendChild(bar);

  // 오류가 있으면 먼저 보인다 — 왜 같은 질문이 다시 왔는지 알아야 하기 때문이다.
  (msg.validationErrors || []).forEach((e) => {
    const err = document.createElement('div');
    err.className = 'subtask-error';
    err.textContent = e.reason;
    bubble.appendChild(err);
  });

  // 질문 문장은 서버가 준 것을 그대로 찍는다 — 클라이언트도 문장을 고치지 않는다(D-44).
  const q = document.createElement('div');
  q.className = 'subtask-question';
  // 단계 안에서의 순번만 붙인다. 전체 50개 중 몇 번째인지는 사용자에게 의미가 없다.
  const n = msg.questionInGroup;
  q.textContent = n ? `질문 ${n}. ${msg.question}` : msg.question;
  bubble.appendChild(q);

  const schema = msg.inputSchema || {};
  if (schema.allowedRange && schema.allowedRange.description) {
    const hint = document.createElement('div');
    hint.className = 'subtask-hint';
    hint.textContent = '허용 범위: ' + schema.allowedRange.description;
    bubble.appendChild(hint);
  }

  const row = document.createElement('div');
  row.className = 'subtask-actions';
  const input = buildSubtaskInput(schema);
  const submit = document.createElement('button');
  submit.className = 'btn btn-primary';
  submit.textContent = '답변';
  const submitAnswer = () => {
    const value = input.value.trim();
    if (!value) return;
    input.disabled = true;
    submit.disabled = true;
    if (skip) skip.disabled = true;
    cancel.disabled = true;
    // 위젯 입력도 일반 메시지와 같은 경로로 보낸다 — 서버의 정규화·검증 순서를
    // 건너뛰는 두 번째 문을 만들지 않는다. 화면 echo도 서버가 돌려주는 USER 메시지에
    // 맡긴다(여기서 따로 그리면 같은 답이 두 번 보인다).
    //
    // currentSubtaskId를 함께 돌려보내는 것이 중요하다: 이 답이 <b>어느 질문에 대한
    // 것인지</b>를 서버가 도착 순서로 추측하지 않게 한다. STOMP 인바운드는 순서를
    // 보장하지 않으므로, 이것이 없으면 답변이 옆 칸에 들어갈 수 있다.
    stompClient.send('/app/chat.send', {}, JSON.stringify({
      type: 'USER', content: value, domain: 'waste',
      currentSubtaskId: msg.currentSubtaskId
    }));
  };
  submit.onclick = submitAnswer;
  input.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); submitAnswer(); } };

  // "해당 없음" 버튼 — 숫자·시각 입력칸에는 그 문구를 <b>타이핑할 수 없기</b> 때문이다
  // (input type=number는 비숫자 값을 버린다). 고정 세트는 관련 없는 항목도 생략하지 않고
  // 묻기로 했으므로, 빠져나갈 문이 자료형과 무관하게 있어야 한다.
  let skip = null;
  if (schema.allowsNotApplicable) {
    skip = document.createElement('button');
    skip.className = 'btn btn-secondary subtask-skip';
    skip.textContent = '해당 없음';
    skip.onclick = () => {
      input.disabled = true;
      submit.disabled = true;
      skip.disabled = true;
      cancel.disabled = true;
      stompClient.send('/app/chat.send', {}, JSON.stringify({
        type: 'USER', content: '해당 없음', domain: 'waste',
        currentSubtaskId: msg.currentSubtaskId
      }));
    };
  }

  const cancel = document.createElement('button');
  cancel.className = 'btn btn-secondary';
  cancel.textContent = '구성 취소';
  cancel.onclick = () => {
    input.disabled = true;
    submit.disabled = true;
    cancel.disabled = true;
    stompClient.send('/app/chat.subtaskCancel', {}, '{}');
  };

  row.appendChild(input);
  row.appendChild(submit);
  if (skip) row.appendChild(skip);
  row.appendChild(cancel);
  bubble.appendChild(row);

  div.appendChild(bubble);
  return div;
}

function buildPreviewBubble(msg) {
  const div = document.createElement('div');
  div.className = 'msg preview';

  const bubble = document.createElement('div');
  bubble.className = 'bubble preview-bubble';
  const p = msg.scenarioPreview || {};

  const title = document.createElement('div');
  title.className = 'preview-title';
  title.textContent = '구성된 시나리오';
  bubble.appendChild(title);

  const meta = document.createElement('div');
  meta.className = 'preview-meta';
  meta.textContent = `${p.scenarioType} · ${p.toolName} · ${p.engineId}`;
  bubble.appendChild(meta);

  const section = (label, rows) => {
    if (!rows || rows.length === 0) return;
    const h = document.createElement('div');
    h.className = 'preview-section';
    h.textContent = label;
    bubble.appendChild(h);
    const ul = document.createElement('ul');
    ul.className = 'preview-list';
    rows.forEach((text) => {
      const li = document.createElement('li');
      li.textContent = text;
      ul.appendChild(li);
    });
    bubble.appendChild(ul);
  };

  // display를 쓴다 — 시각의 구조화 값은 자정 기준 분(510)이라 그대로 찍으면
  // "조건을 확인하는" 화면에서 확인이 되지 않는다.
  section('내가 답한 값', Object.values(p.answers || {}).map(
    (a) => `${a.field}: ${a.display !== undefined ? a.display : a.value}`));
  // 서버가 채운 값과 가정은 반드시 보인다 — 조용히 채우고 결과만 내면 사용자는
  // 자기 실험의 조건을 모른 채 숫자를 읽는다(D-53).
  section('서버가 채운 값', (p.appliedDefaults || []).map(
    (d) => `${d.field} = ${d.value} — ${d.reason}`));
  section('가정', p.assumptions || []);

  const row = document.createElement('div');
  row.className = 'confirm-actions';
  const run = document.createElement('button');
  run.className = 'btn btn-primary';
  run.textContent = '이 조건으로 실행';
  const cancel = document.createElement('button');
  cancel.className = 'btn btn-secondary';
  cancel.textContent = '취소';
  run.onclick = () => {
    run.disabled = true;
    cancel.disabled = true;
    run.textContent = '실행 중...';
    stompClient.send('/app/chat.subtaskRun', {}, '{}');
  };
  cancel.onclick = () => {
    run.disabled = true;
    cancel.disabled = true;
    stompClient.send('/app/chat.subtaskCancel', {}, '{}');
  };
  row.appendChild(run);
  row.appendChild(cancel);
  bubble.appendChild(row);

  div.appendChild(bubble);
  return div;
}

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
    '원하는 조건을 한 문장으로 적어주세요. 무엇이 필요한지 모르겠다면 ' +
    '"시뮬레이터 만들어 줘"라고 하시면 필요한 값을 순서대로 여쭤봅니다.',
  /** 장량동 고유 메시지 타입만 가로챈다. 처리하지 않으면 false → chat.js 기본 렌더러. */
  renderMessage(msg) {
    if (msg.type === 'SUBTASK') {
      document.getElementById('messages').appendChild(buildSubtaskBubble(msg));
      return true;
    }
    if (msg.type === 'PREVIEW') {
      document.getElementById('messages').appendChild(buildPreviewBubble(msg));
      return true;
    }
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
