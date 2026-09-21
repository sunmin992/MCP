# T2-tree: 구성 개체와 상태 개체를 연결하는 추출

## 실행
키트 디렉터리에서 새 run-id를 사용한다. 기존 실험 디렉터리에 덮어쓰지 않는다.

```powershell
python extract.py pipeline --run-id q3-tree-1 --rule P-pilot --plan T2-tree --provider ollama --model qwen3-coder:30b --num-ctx 98304 --line-numbers
```

## 변경
9단계: a / b2 / g / c / d / r2 / e / f / b3.
g는 코드의 대상 경계·구성 개체를 별도로 제안한다.
상태 없는 boundary/set/type에는 identity_evidence와 composition_evidence가 필요하다.
b2 상태 개체의 네 근거 규칙은 유지한다.

d는 g와 b2 후보를 함께 보고 ASPECT/SPEC/MULTI 관계를 제안한다.
기존 개체를 연결할 때도 member_evidence의 자식별 인용이 필수다.
MULTI는 multiplicity.evidence, SPEC은 selection을 요구한다.
ASPECT는 자식을 새로 만들지 않는다. 누락 개체는 보류한다.
r2는 여전히 새 개체를 만들지 않는다. g에서 빠진 구성은 다음 실험에서 보완한다.

이름 목록과 루트 후보는 T2-tree에서 실제 조립 결과로 계산한다.
구성 인용과 자식별 인용은 기존 스냅샷 대조와 의미 검토 대상으로 보존한다.
TREE_COMPLETE는 단일 루트·단일 부모·비순환·전체 도달을 차단 검사로 추가한다.
검토자가 루트를 선택해도 분리된 그래프를 완성 트리로 통과시키지 않는다.
기존 경로의 프롬프트와 문맥 의존성은 유지한다.

## 경계
첨부 그림의 노드 이름이나 참조 SES를 프롬프트에 주입하지 않았다.
현재 scope는 simulation_target이다. 첨부 그림에 있는 실행 엔진·설정·결과를
포함한 소프트웨어 전체 구성은 별도 범위 설계가 필요하며 자동으로 도메인 개체에 섞지 않는다.
근거 형식과 원문 일치가 의미 정당성을 입증하지는 않는다. 사람 승인 절차를 유지한다.
단일 트리를 강제로 만들지 않으며 근거 부족·unknown·원시 후보를 보존한다.
전체 코드 입력 분할은 이 변경에 포함되지 않는다.

## 검증
122개 단위/통합 시험 통과. 고정 응답으로 9단계 실행 및 재개를 확인했다.
실제 qwen3-coder 추출은 아직 수행하지 않았으므로 실제 추출 품질은 미확인이다.

