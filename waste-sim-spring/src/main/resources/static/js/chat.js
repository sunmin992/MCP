/*
 * 도메인 공통 채팅 계층 — WebSocket 연결, 메시지 렌더링, 입력 처리.
 *
 * 여기에는 어느 도메인에서도 똑같이 동작하는 것만 둔다. 장량동 결과 카드나 엣지
 * 발열 그래프처럼 도메인마다 다른 렌더링은 각 도메인 모듈(js/waste.js, js/edge.js)이
 * renderMessage()로 가져간다 — 이 파일에 도메인 이름이 등장하기 시작하면 도메인을
 * 추가할 때마다 공통 코드를 고쳐야 하고, 그게 파일을 나눈 이유를 무너뜨린다.
 */

let stompClient = null;

// ── WebSocket 연결 ────────────────────────────────────────────────
function connect() {
  const socket = new SockJS('/ws');
  stompClient = Stomp.over(socket);
  stompClient.debug = null;

  stompClient.connect({}, () => {
    document.getElementById('statusDot').classList.add('connected');
    document.getElementById('connStatus').textContent = '연결됨';
    document.getElementById('sendBtn').disabled = false;

    stompClient.subscribe('/topic/messages', (frame) => {
      const msg = JSON.parse(frame.body);
      appendMessage(msg);
    });
  }, (err) => {
    document.getElementById('connStatus').textContent = '연결 끊김 — 재시도 중...';
    document.getElementById('statusDot').classList.remove('connected');
    document.getElementById('sendBtn').disabled = true;
    setTimeout(connect, 3000);
  });
}

// 마크다운 강조 기호(**, *, __, `, # 등)를 제거해 평문으로 표시
function stripMarkdown(s) {
  if (!s) return s;
  return s
    .replace(/\*\*(.*?)\*\*/g, '$1')   // **bold**
    .replace(/__(.*?)__/g, '$1')       // __bold__
    .replace(/\*(.*?)\*/g, '$1')       // *italic*
    .replace(/`([^`]+)`/g, '$1')       // `code`
    .replace(/^#{1,6}\s+/gm, '')       // # 제목
    .replace(/^\s*>\s?/gm, '');        // > 인용
}

// ── 메시지 렌더링 ─────────────────────────────────────────────────
function appendMessage(msg) {
  const container = document.getElementById('messages');

  // 도메인 고유 메시지 타입(장량동 RESULT/SCENARIO 등)은 해당 도메인이 그린다.
  const domain = Domains.current();
  if (domain && typeof domain.renderMessage === 'function' && domain.renderMessage(msg)) {
    container.scrollTop = container.scrollHeight;
    return;
  }

  if (msg.type === 'CONFIRM') {
    container.appendChild(buildConfirmBubble(msg));
  } else {
    const div = document.createElement('div');
    div.className = 'msg ' + msg.type.toLowerCase();
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.textContent = stripMarkdown(msg.content);
    div.appendChild(bubble);
    container.appendChild(div);
  }

  container.scrollTop = container.scrollHeight;
}

// AI가 자동 실행을 확신할 수 없을 때(예: 예시성 답변, 다중 JSON 등)
// 즉시 실행하지 않고 사용자 확인을 받는 버블
function buildConfirmBubble(msg) {
  const div = document.createElement('div');
  div.className = 'msg confirm';

  const bubble = document.createElement('div');
  bubble.className = 'bubble confirm-bubble';

  const text = document.createElement('div');
  text.textContent = stripMarkdown(msg.content);
  bubble.appendChild(text);

  const btnRow = document.createElement('div');
  btnRow.className = 'confirm-actions';

  const runBtn = document.createElement('button');
  runBtn.className = 'btn btn-primary confirm-run-btn';
  runBtn.textContent = '예, 이 설정으로 실행';
  runBtn.onclick = () => {
    stompClient.send('/app/chat.confirmRun', {}, '{}');
    runBtn.disabled = true;
    runBtn.textContent = '실행 중...';
    cancelBtn.disabled = true;
  };

  const cancelBtn = document.createElement('button');
  cancelBtn.className = 'btn btn-secondary confirm-cancel-btn';
  cancelBtn.textContent = '아니오';
  cancelBtn.onclick = () => {
    runBtn.disabled = true;
    cancelBtn.disabled = true;
    text.textContent += ' (취소됨)';
  };

  btnRow.appendChild(runBtn);
  btnRow.appendChild(cancelBtn);
  bubble.appendChild(btnRow);
  div.appendChild(bubble);
  return div;
}

function appendLocalMessage(type, content) {
  const container = document.getElementById('messages');
  const div = document.createElement('div');
  div.className = 'msg ' + type;
  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  bubble.textContent = content;
  div.appendChild(bubble);
  container.appendChild(div);
  container.scrollTop = container.scrollHeight;
}

// ── 메시지 전송 ───────────────────────────────────────────────────
function send() {
  const input = document.getElementById('msgInput');
  const text = input.value.trim();
  if (!text || !stompClient) return;

  // 현재 화면의 도메인을 같이 보낸다. 도메인이 하나뿐이라 서버가 이 값으로
  // 무엇을 판정하지는 않지만, 메시지 계약을 그대로 두어 클라이언트·서버 양쪽의
  // 형식이 어긋나지 않게 한다.
  stompClient.send('/app/chat.send', {}, JSON.stringify({
    type: 'USER',
    content: text,
    domain: Domains.currentId()
  }));
  input.value = '';
  input.style.height = 'auto';
}

function handleKey(e) {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
  // 자동 높이 조절
  const ta = document.getElementById('msgInput');
  setTimeout(() => {
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 120) + 'px';
  }, 0);
}

function clearChat() {
  if (stompClient) stompClient.send('/app/chat.clear', {}, '{}');
  document.getElementById('messages').innerHTML = '';
}

// ── 초기화 ────────────────────────────────────────────────────────
// 화면을 먼저 세운 뒤 연결한다 — 반대로 하면 연결 직후 도착한 메시지가
// 아직 없는 사이드바를 참조할 수 있다.
Domains.boot();
connect();
