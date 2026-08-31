/*
 * 도메인 레지스트리 — 이 서버가 서비스하는 시뮬레이션 도메인을 프론트에서 다루는 방식.
 *
 * 사이드바 마크업·인사말·도메인 고유 렌더러를 한 곳에 모아 두는 구조다. 지금은 도메인이
 * 장량동 하나뿐이지만, 화면 구성 요소가 index.html·chat.js·CSS로 흩어지지 않는다는 이득은
 * 그대로라 레지스트리를 유지한다.
 *
 * 새 도메인 추가 절차 (이 세 가지가 전부):
 *   1. index.html 에 <template id="tpl-sidebar-{id}"> 로 사이드바 마크업 추가
 *   2. js/{id}.js 에서 Domains.register({...}) 호출
 *   3. index.html 에 <script src="js/{id}.js"> 한 줄 추가
 * chat.js·app.css·서버 라우팅은 건드리지 않는다.
 *
 * id 값은 WebSocket 메시지의 domain 필드와 같아야 한다. 엣지 도메인이 함께 있던
 * 시절에는 이 값이 URL(/waste)과 MCP 엔드포인트(/mcp/waste)까지 공유했지만, 도메인이
 * 하나가 되면서 주소는 /와 /mcp 하나씩만 남았다.
 */
const Domains = (() => {

  const registry = new Map();   // id -> 도메인 정의
  let active = null;            // 현재 활성 도메인 정의 (boot() 전에는 null)

  /**
   * 도메인 정의 하나를 등록한다.
   *
   * 정의 필드:
   *   id        {string}  WebSocket 메시지의 domain 필드와 같은 식별자.
   *   label     {string}  시작화면 카드 제목.
   *   icon      {string}  카드 아이콘(이모지 1자).
   *   tagline   {string}  카드 한 줄 설명.
   *   title     {string}  이 도메인 화면일 때의 document.title.
   *   greeting  {string}  화면 진입 시 띄우는 첫 봇 메시지.
   *   placeholder {string} 입력창 placeholder.
   *   init      {function} 사이드바가 DOM에 붙은 직후 1회 호출(이벤트 리스너 등록 등).
   *   renderMessage {function(msg): boolean}
   *             도메인 고유 메시지 타입을 직접 그린다. 처리했으면 true를 반환하고,
   *             false면 chat.js의 기본 렌더러(평문 버블)가 이어받는다.
   */
  function register(def) {
    registry.set(def.id, def);
  }

  function all() {
    return Array.from(registry.values());
  }

  function get(id) {
    return registry.get(id) || null;
  }

  function current() {
    return active;
  }

  /** 현재 활성 도메인의 id. 시작화면이면 null — 이 값이 서버로 가는 domain 필드가 된다. */
  function currentId() {
    return active ? active.id : null;
  }

  /**
   * 도메인을 활성화한다 — 사이드바 교체 + URL 전환 + 제목/입력창/칩 갱신.
   *
   * @param id {string} 활성화할 도메인 id
   */
  function activate(id) {
    const def = registry.get(id);
    if (!def) return;
    if (active && active.id === def.id) return;   // 이미 그 도메인이면 아무 것도 안 함

    active = def;
    mountSidebar(def);

    document.title = def.title;
    const input = document.getElementById('msgInput');
    if (input) input.placeholder = def.placeholder;

    if (typeof def.init === 'function') def.init();
    if (def.greeting) appendLocalMessage('bot', def.greeting);
  }

  /**
   * 사이드바를 해당 도메인의 <template> 내용으로 교체한다.
   *
   * <template>을 쓰는 이유: 비활성 도메인의 마크업이 DOM에 남아 있으면
   * document.getElementById('eBoard') 같은 조회가 <b>다른 도메인의</b> 입력칸을
   * 집어올 수 있다. template 안의 내용은 DOM 트리에 없으므로 그런 id 충돌이
   * 구조적으로 생기지 않는다 — 도메인마다 id를 접두어로 구분하는 규칙에
   * 의존하지 않아도 된다.
   */
  /**
   * 사이드바를 해당 도메인의 <template> 내용으로 교체한다.
   *
   * <template>을 쓰는 이유: 비활성 도메인의 마크업이 DOM에 남아 있으면
   * document.getElementById('eBoard') 같은 조회가 <b>다른 도메인의</b> 입력칸을
   * 집어올 수 있다. template 안의 내용은 DOM 트리에 없으므로 그런 id 충돌이
   * 구조적으로 생기지 않는다 — 도메인마다 id를 접두어로 구분하는 규칙에
   * 의존하지 않아도 된다.
   */
  function mountSidebar(def) {
    const host = document.getElementById('sidebar');
    if (!host) return;
    const tplId = def ? 'tpl-sidebar-' + def.id : 'tpl-sidebar-start';
    const tpl = document.getElementById(tplId);
    host.innerHTML = '';
    if (tpl) host.appendChild(tpl.content.cloneNode(true));
  }

  /**
   * 최초 로드 시 화면을 세운다.
   *
   * <p>고를 것이 없으므로 시작화면도 없다 — 등록된 유일한 도메인을 바로 활성화한다.
   * 라즈베리파이 엣지 도메인이 함께 있던 시절에는 여기서 도메인 선택 카드를 그리고
   * URL(/waste·/edge)로 화면을 복원했다. 도메인이 하나가 되면서 주소도 하나(/)가 됐고,
   * 그래서 pushState·popstate·fromPath()가 전부 필요 없어졌다.
   *
   * <p>레지스트리 구조 자체는 남겼다. 사이드바 마크업·인사말·도메인 고유 렌더러를
   * 한 곳에 모아 두는 이득은 도메인이 하나여도 그대로다.
   */
  function boot() {
    const first = all()[0];
    if (first) activate(first.id);
  }

  return { register, all, get, current, currentId, activate, boot };
})();
