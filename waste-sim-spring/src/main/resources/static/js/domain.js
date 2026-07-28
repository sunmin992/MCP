/*
 * 도메인 레지스트리 — 이 서버가 서비스하는 시뮬레이션 도메인을 프론트에서 다루는 방식.
 *
 * 서버 쪽 McpDomain / SimulationModelRegistry가 "도구 자신이 자기 도메인을 선언하고
 * 레지스트리가 모아 준다"는 구조인데, 프론트도 같은 모양으로 맞춘다. 도메인을 하나
 * 추가할 때 손댈 곳이 index.html·chat.js·CSS로 흩어지지 않게 하는 것이 목적이다.
 *
 * 새 도메인 추가 절차 (이 세 가지가 전부):
 *   1. index.html 에 <template id="tpl-sidebar-{id}"> 로 사이드바 마크업 추가
 *   2. js/{id}.js 에서 Domains.register({...}) 호출
 *   3. index.html 에 <script src="js/{id}.js"> 한 줄 추가
 * chat.js·app.css·서버 라우팅은 건드리지 않는다.
 *
 * id 값은 서버의 McpDomain.slug() 와 반드시 같아야 한다 — URL(/waste), MCP
 * 엔드포인트(/mcp/waste), WebSocket 메시지의 domain 필드가 전부 이 문자열을 공유한다.
 */
const Domains = (() => {

  const registry = new Map();   // id -> 도메인 정의
  let active = null;            // 현재 활성 도메인 정의 (시작화면에서는 null)

  /**
   * 도메인 정의 하나를 등록한다.
   *
   * 정의 필드:
   *   id        {string}  McpDomain.slug()와 동일한 식별자. URL·MCP 경로·메시지 필드 공용.
   *   label     {string}  시작화면 카드 제목.
   *   icon      {string}  카드 아이콘(이모지 1자).
   *   tagline   {string}  카드 한 줄 설명.
   *   title     {string}  이 도메인 화면일 때의 document.title.
   *   greeting  {string}  화면 진입 시 띄우는 첫 봇 메시지.
   *   placeholder {string} 입력창 placeholder.
   *   chips     {Array<{label,text}|{label,run}>} 예시 질문 칩. run이 있으면 클릭 시 그 함수를 부른다.
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
   * 현재 URL 경로에서 도메인을 읽는다. 새로고침·북마크·뒤로가기가 전부 이 함수를
   * 거치므로, 주소가 곧 화면 상태라는 성질이 유지된다.
   */
  function fromPath() {
    const seg = window.location.pathname.replace(/^\/+|\/+$/g, '');
    return registry.has(seg) ? seg : null;
  }

  /**
   * 도메인을 활성화한다 — 사이드바 교체 + URL 전환 + 제목/입력창/칩 갱신.
   *
   * @param id       {string|null} 활성화할 도메인. null이면 도메인 중립 시작화면.
   * @param options  {{push?: boolean}} push=false면 history를 건드리지 않는다
   *                 (popstate 처리·최초 로드에서 사용 — 여기서 pushState를 또 하면
   *                 뒤로가기가 같은 화면을 두 번 통과하게 된다).
   */
  function activate(id, options) {
    const push = !options || options.push !== false;
    const def = id ? registry.get(id) : null;
    if (id && !def) return;                    // 모르는 도메인은 무시(시작화면 유지)
    if (active && def && active.id === def.id) return;  // 이미 그 도메인이면 아무 것도 안 함

    active = def;
    mountSidebar(def);
    renderChips(def);

    document.title = def ? def.title : '시뮬레이션 허브';
    const input = document.getElementById('msgInput');
    if (input) {
      input.placeholder = def
        ? def.placeholder
        : '어떤 실험을 하고 싶은지 한 문장으로 적어 주세요...';
    }

    const path = def ? '/' + def.id : '/';
    if (push && window.location.pathname !== path) {
      history.pushState({ domain: def ? def.id : null }, '', path);
    }

    // 시작화면 표시 여부는 여기 한 곳에서만 결정한다 — 카드 클릭·자연어 판정·
    // URL 직접 진입·뒤로가기가 전부 이 함수를 통과하므로, 호출부마다 따로
    // 숨기고 보이면 어느 한 경로에서 어긋나기 쉽다.
    if (def) hideStartScreen(); else showStartScreen();

    if (def && typeof def.init === 'function') def.init();
    if (def && def.greeting) appendLocalMessage('bot', def.greeting);
  }

  /**
   * 사이드바를 해당 도메인의 <template> 내용으로 교체한다.
   *
   * <template>을 쓰는 이유: 비활성 도메인의 마크업이 DOM에 남아 있으면
   * document.getElementById('pDays') 같은 조회가 <b>다른 도메인의</b> 입력칸을
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

  /** 입력창 위의 예시 질문 칩을 현재 도메인 것으로 갈아끼운다. */
  function renderChips(def) {
    const host = document.getElementById('chips');
    if (!host) return;
    host.innerHTML = '';
    const chips = (def && def.chips) || [];
    chips.forEach(chip => {
      const el = document.createElement('div');
      el.className = 'chip';
      el.textContent = chip.label;
      el.onclick = () => (chip.run ? chip.run() : sendChip(chip.text));
      host.appendChild(el);
    });
  }

  /**
   * 시작화면의 도메인 선택 카드를 그린다. 등록된 도메인을 그대로 순회하므로
   * 도메인을 추가해도 이 함수는 손댈 필요가 없다.
   */
  function renderStartCards() {
    const host = document.getElementById('domainCards');
    if (!host) return;
    host.innerHTML = '';
    all().forEach(def => {
      const card = document.createElement('button');
      card.className = 'domain-card';
      card.innerHTML =
        `<span class="dc-icon">${def.icon}</span>` +
        `<span class="dc-label">${def.label}</span>` +
        `<span class="dc-tagline">${def.tagline}</span>`;
      card.onclick = () => activate(def.id);
      host.appendChild(card);
    });
  }

  function showStartScreen() {
    const el = document.getElementById('startScreen');
    if (el) el.classList.remove('hidden');
  }

  function hideStartScreen() {
    const el = document.getElementById('startScreen');
    if (el) el.classList.add('hidden');
  }

  /**
   * 최초 로드 시 화면 상태를 URL에 맞춘다. /waste·/edge로 직접 들어오거나
   * 새로고침해도 그 도메인 화면이 그대로 복원된다.
   */
  function boot() {
    renderStartCards();
    const fromUrl = fromPath();
    if (fromUrl) {
      activate(fromUrl, { push: false });
    } else {
      mountSidebar(null);
      renderChips(null);
      showStartScreen();
    }

    // 뒤로/앞으로 가기 — 주소가 바뀌면 화면도 따라간다.
    window.addEventListener('popstate', () => {
      active = null;               // activate()의 "같은 도메인이면 무시" 가드를 풀어준다
      activate(fromPath(), { push: false });
    });
  }

  return {
    register, all, get, current, currentId,
    activate, boot, hideStartScreen, showStartScreen
  };
})();
