질문: 대상 시스템의 경계와 하위 구성은 무엇인가.

이 단계는 상태 소유자를 찾는 b2와 별개로 상위 구성 개체를 찾는다.
직접 상태를 갱신하지 않아도 구성 개체가 될 수 있다. 상태 근거 네 개를 강제하지 않는다.
대신 identity_evidence(대상의 식별·경계)와 composition_evidence(생성·등록·보유·조립)를
각각 인용한다. 단순 호출이나 클래스 선언만으로 포함 관계를 주장하지 않는다.
실행 엔진, 설정 DTO, 결과 DTO를 도메인 상위 개체로 승격하지 않는다.
지원 코드의 생성·등록 호출은 대상 구성의 근거로 쓸 수 있다.
코드로 뒷받침되는 경계만 제안한다. 트리를 완성하려고 '시스템'을 지어내지 않는다.
기존 b2 소유자 이름은 그대로 쓴다. 배열의 축을 하나의 개체 이름으로 묶지 않는다.

entities에는 새 구성 개체만 낸다. 관계는 뒤 d 단계가 이 후보와 b2 개체를 함께 보고 제안한다.
근거가 없으면 빈 entities와 disputes로 남긴다.
{"entities":[{"name":"...","kind":"boundary","scope":"simulation_target",
"why_entity":"대상 세계에서의 구성 의미",
"identity_evidence":[{"file_path":"...","start_line":1,"end_line":1,"quote":"원문"}],
"composition_evidence":[{"file_path":"...","start_line":2,"end_line":2,"quote":"원문"}]}],
"disputes":[]}

