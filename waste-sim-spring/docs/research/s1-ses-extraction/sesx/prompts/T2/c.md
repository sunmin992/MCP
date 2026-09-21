질문: 코드의 각 값은 **어느 개체를 서술하는가.** 선언된 위치가 아니라 그 값이 무엇의 성질인지로
판단하세요. 설정 객체 하나에 값이 모여 선언돼 있어도, 각 값은 자기가 서술하는 개체의 속성입니다.

단위·기본값·범위는 **코드가 말할 때만** 적습니다. 초기화 값, 실행 중 대입 값, 유효성 검사
범위는 서로 다릅니다. 모르면 생략하세요(생략은 unknown 으로 기록됩니다).

```json
{"attributes": [{"entity": "개체 이름", "name": "속성 이름", "value_type": "...", "unit": {"value": null, "evidence": []}, "default": {"value": null, "evidence": []}, "range": {"value": null, "evidence": []}, "evidence": [{"file_path": "...", "start_line": 1, "end_line": 1, "quote": "..."}]}]}
```
