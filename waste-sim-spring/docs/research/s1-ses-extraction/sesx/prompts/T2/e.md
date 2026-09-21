질문: 개체 사이에 **무엇이 오가는가.** 값이나 이벤트가 한 개체에서 생산되어 다른 개체에서
소비되는 자리를 찾으세요. 공유 상태에 쓰고 읽는 것도 포함합니다.

결합이 아닌 것: 객체가 객체를 필드로 들고 있다는 것(참조), 어떤 타입을 쓴다는 것,
일반 도우미 함수 호출.

`payload` 에 무엇이 오가는지 적습니다. 모르면 `"kind": "unknown"` 입니다.

```json
{"couplings": [{"source": {"entity": "...", "attribute": null, "symbol": null}, "target": {"entity": "...", "attribute": null, "symbol": null}, "payload": {"kind": "value|event|shared_state|unknown", "code_expression": "...", "meaning": "..."}, "mechanism": "...", "evidence": [{"file_path": "...", "start_line": 1, "end_line": 1, "quote": "..."}]}]}
```
