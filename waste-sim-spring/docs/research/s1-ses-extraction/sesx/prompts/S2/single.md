질문: 이 코드가 시뮬레이션하는 대상의 구조를 SES로 추출하세요. 개체·속성·구성 관계·결합·
활성 조건을 **한 번에** 냅니다.

- ASPECT — 함께 존재하며 동시에 돈다 · SPEC — 하나를 고른다 · MULTI — 동종 개체 여럿
- 결합은 값·이벤트·공유 상태가 개체 사이를 오가는 자리다. 참조 보유·타입 사용은 아니다
- 활성 조건의 기본값은 `unknown` 이다. 호출자 조건을 확인하지 못했으면 `coverage` 는 incomplete 다

```json
{"entities": [{"name": "...", "kind": "stateful", "scope": "simulation_target", "why_entity": "...", "evidence": []}],
 "attributes": [{"entity": "...", "name": "...", "value_type": null, "evidence": []}],
 "decompositions": [{"parent": "...", "kind": "ASPECT|SPEC|MULTI", "members": ["..."], "selection": null, "multiplicity": {"count_expression": null}, "activation": {"state": "unknown", "clauses": [], "combination": null, "coverage": "incomplete"}, "evidence": []}],
 "couplings": [{"source": {"entity": "...", "attribute": null}, "target": {"entity": "...", "attribute": null}, "payload": {"kind": "unknown", "code_expression": null, "meaning": null}, "mechanism": null, "activation": {"state": "unknown", "clauses": [], "combination": null, "coverage": "incomplete"}, "evidence": []}]}
```

모든 항목의 `evidence` 는 공통 규칙의 형식을 따릅니다.
