질문: 앞 단계의 관찰 중 **무엇이 대상 세계의 하나의 개체인가.** 같은 것을 두 이름으로 부른
것은 하나로 합치고, 개체가 아닌 것(값·계산 결과·실행 환경)은 빼세요.

이름 규칙: **명사구 하나**로 적습니다. 괄호·쉼표·'및'·'와'로 둘을 묶지 않습니다.

`kind` 는 stateful(상태가 변한다) · boundary(시스템 경계) · set(동종 개체의 모음) ·
type(유형) · unknown 중 하나입니다. 경계·유형을 상태 변화 개체로 위장하지 마세요.
`scope` 는 simulation_target 또는 support_software 입니다.

```json
{"entities": [{"name": "...", "kind": "stateful", "scope": "simulation_target", "why_entity": "...", "evidence": [{"file_path": "...", "start_line": 1, "end_line": 1, "quote": "..."}]}]}
```
