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
  ]
});
