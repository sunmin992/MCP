질문: 이 코드는 대상 세계가 **무엇들로 이루어져 있다**고 선언하는가.

앞 단계는 "시간이 흐르면 무엇이 달라지는가"를 물었습니다. 그래서 나온 것은 대개 배열의
**색인 축**입니다. 이번에는 다른 것을 봅니다 — **선언**입니다. 갱신되는 줄이 아니라,
"이런 종류의 것이 있다"고 적어 둔 줄입니다.

역할 셋 중 하나로 답합니다.

### `set` — 같은 종류 여럿을 담는다

배열이나 컬렉션의 **선언**을 인용합니다. 원소 유형을 `element_type` 에 적으세요.

- 인용할 것: `private final List<CollectionSite> sites;` · `new WasteType[n]`
- 인용하면 안 되는 것: 그 배열에 값을 넣는 줄, 읽는 줄

### `type` — 택일 가능한 한 종류

열거 상수나 하위 유형의 **선언**을 인용합니다. 같은 축의 다른 값을 `siblings` 에 적으세요.
형제를 못 대면 그것은 축이 아닙니다.

- 인용할 것: 열거 상수가 선언된 줄 그 자체
- `siblings` 없이 하나만 적지 마세요

### `object` — 식별되어 만들어지는 것

유형 선언과 **그것이 만들어지거나 등록되는 자리**를 둘 다 인용합니다.
`declaration_evidence` 에 선언을, `instantiation_evidence` 에 생성·등록을 적습니다.

**클래스가 있다는 것만으로는 개체가 아닙니다.** 시뮬레이션이 도는 동안 그것이 만들어지거나
어딘가에 등록되는 자리가 있어야 합니다. 생성 자리를 못 찾으면 적지 마세요.

## 적지 말아야 할 것

- **클래스 목록을 옮기지 마세요.** 파일마다 하나씩 세는 식으로 답하면 전부 보류됩니다.
- 실행을 돌리는 장치 — 큐·스케줄러·컨트롤러·서비스·전송·설정 읽기·로깅
- 결과를 담아 내보내는 객체 — 그것은 관측이지 대상 세계의 개체가 아닙니다
- 앞 단계가 이미 **상태**로 확정한 이름 (목록이 위에 주어집니다)

## 모르면 비워 두세요

`{"entities": []}` 도 답입니다. 근거가 모양에 맞지 않으면 코드가 보류하므로, 억지로 채우면
보류만 늘어납니다.

```json
{"entities": [
  {"name": "...",
   "role": "set|type|object",
   "scope": "simulation_target",
   "why_in_world": "이것이 대상 세계의 무엇인가. 한 문장",
   "element_type": "set 일 때만 — 원소의 유형 이름",
   "siblings": ["type 일 때만 — 같은 축의 다른 값들"],
   "declaration_evidence": [{"file_path": "...", "start_line": 1, "end_line": 1, "quote": "...", "symbol": null}],
   "instantiation_evidence": [{"file_path": "...", "start_line": 1, "end_line": 1, "quote": "...", "symbol": null}],
   "evidence": [{"file_path": "...", "start_line": 1, "end_line": 1, "quote": "...", "symbol": null}]}]}
```

`evidence` 는 그것이 존재한다는 근거입니다. `declaration_evidence` 와 같아도 됩니다.
