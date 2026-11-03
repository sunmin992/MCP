# 재테스트 결과

2026-11-03, 제품 코드 변경 없이 현재 작업 트리를 재검증했다.

| 대상 | 전체 | 통과 | 실패/오류 | 스킵 |
|---|---:|---:|---:|---:|
| broker | 155 | 155 | 0 | 0 |
| simulator | 692 | 689 | 0 | 3 |
| template-generator | 45 | 45 | 0 | 0 |
| parking-sim | 89 | 89 | 0 | 0 |
| counter-sim | 31 | 31 | 0 | 0 |
| district-waste-sim | 40 | 40 | 0 | 0 |
| 합계 | 1052 | 1049 | 0 | 3 |

루트에서 `.\mvnw.cmd -B clean install`, 독립 제공자 세 곳에서 각각 `.\mvnw.cmd -B clean package`를 실행했다.
네 빌드 모두 BUILD SUCCESS이며, 실패 무시 옵션을 사용하지 않았다.
스킵 3건은 Python 참조 엔진 부재에 따른 기존 PythonWasteSimAdapterTest의 조건부 중단이다.
집계는 [test-summary.json](test-summary.json), 실행 로그는 같은 폴더의 *-test.log에 있다.

재빌드한 로컬 브로커와 실제 제공자 두 곳을 띄워 `scripts/demo_requirement_flow.py`의 11개 통합 사례를 다시 통과했다.
선택·복수 후보·미지원 해상도·두 지역 구성·누락 입력·기간 불일치·미승인 실행 거절·실패한 수정 후 STALE을 확인했다.
원문 응답은 [live-demo.json](live-demo.json)에 저장했다. 사용자 승인은 발급하지 않았고 검증 서버는 종료했다.

자연어 추출 채점기는 필드 정확도·임의 추가 필드 집계, 빈 예측·중복·모델 버전 혼합 거절을 재확인했다.
이는 채점기 자체 검사이며 실제 LLM 추출 정확도 측정은 아니다.
`git diff --check`도 통과했다. UI 시각 검사와 새 실지역 데이터 검증은 이번 재테스트에 포함하지 않았다.
