질문: 앞 단계의 결합과 분해가 **언제 활성인가.**

조건은 그 자리의 분기만이 아닙니다. 호출자가 조건부로 부를 수도 있고, 설정값이 경로 전체를
끄기도 합니다. 확인한 범위를 `coverage` 에 적으세요 — 호출 경로를 전부 보지 못했으면
`incomplete` 입니다.

- `known` — 조건을 특정했다. `clauses` 에 원문 표현식과 기호를 적는다
- `unconditional` — 조건이 **없음을** 확인했다. 확인 범위가 complete 일 때만 쓴다
- `unknown` — 모른다. **이것이 기본값이다**

절이 여럿이면 `combination` 에 "AND" 또는 "OR" 를 적습니다. 모르면 null 입니다.

```json
{"activation": [{"target": {"kind": "coupling", "ref": {"source_entity": "...", "target_entity": "..."}}, "state": "unknown", "clauses": [{"expression": "...", "symbol": "...", "evidence": [{"file_path": "...", "start_line": 1, "end_line": 1, "quote": "..."}]}], "combination": null, "coverage": "incomplete", "evidence": []}]}
```

분해를 가리킬 때는 `{"kind": "decomposition", "ref": {"parent": "...", "kind": "SPEC"}}` 입니다.
