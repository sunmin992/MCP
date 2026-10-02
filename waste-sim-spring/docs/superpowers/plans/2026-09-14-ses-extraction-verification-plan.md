# 소스 코드 → 검토 가능한 SES 초안: 추출·검증 파이프라인 구현계획

작성 2026-09-14 · 대상 저장소 `C:\Dev\MCP\waste-sim-spring` · 기준 HEAD `12937be` (develop)

이 문서는 **SES 초안을 안정적으로 뽑고, 코드 근거로 검증하고, 구조적으로 검사해
전문가가 검토할 수 있는 상태로 확정**하는 데까지만 다룬다. 실행 매개변수 연결과 운영
반영은 다음 단계로 미룬다.

---

## 1. 문제와 시스템 경계

### 1.1 구현 목적

소스 코드에서 SES 초안을 뽑되, **모든 항목이 코드의 어느 줄에서 왔는지 기계가 다시 확인할
수 있는 형태**로 뽑는다. 목적은 정답 SES를 한 번 맞히는 것이 아니라, 다음 세 가지를
수치로 말할 수 있게 만드는 것이다.

1. 같은 코드에서 같은 구조가 반복해서 나오는가 (반복 안정성)
2. 나온 항목이 코드에 실재하는가 (근거 검증 통과율)
3. 나온 구조가 SES 형식론으로 성립하는가 (구조 검증 통과율)

정확도(참조 대비 F1)는 네 번째다. 참조 자체가 사람이 만든 가설이므로, 근거·구조 검증을
통과하지 못한 결과의 F1은 해석하지 않는다.

### 1.2 입력과 출력

**입력**
- 선별 규칙 하나(코드로 표현된, 도메인 지식이 없는 규칙)
- 그 규칙이 고른 파일들의 본문
- 모델 설정(모델명·temperature·max_tokens·response_format)

**출력** (한 실행당 하나의 디렉터리)
- `ses.json` — 데이터 계약 v1을 따르는 SES 초안 (§4)
- `snapshot.json` — 파일별 SHA-256, 행 수, git rev, 선별 규칙
- `stages/*.json` — 단계별 프롬프트·원문 응답·모델 설정·토큰·소요시간
- `validation.json` — 스키마/근거/구조/의미 검사 결과 전체 (통과·실패·미확정)
- `unresolved.json` — 확정하지 못한 항목과 그 이유
- `report.md` — 사람이 읽는 요약

### 1.3 포함 범위

- 입력 선별과 스냅샷 고정
- 단계별 추출의 분리·저장·재개
- Entity / Attribute / ASPECT / SPEC / MULTI / Coupling / 활성 조건 추출
- 근거(파일·행 범위·기호·인용문) 저장과 **결정적 재검증**
- 참조 무결성·단일 루트·순환·고립·도달 가능성 검사
- ASPECT/SPEC/MULTI 의미 규칙 검사(기계가 가능한 범위)
- Coupling·활성 조건 채점 지표 추가
- 단일 추출(S 계열) ↔ 단계별 추출(T 계열) 비교 실험 설계
- 실행 매개변수 연결은 **후보 목록 생성까지만**

### 1.4 제외 범위

- 시뮬레이터 자동 생성, 운영 시스템 반영
- 범용 온톨로지·그래프 DB 구축
- 매개변수 값·단위·범위의 자동 확정 (후보 제시까지만)
- 참조 SES(`reference-ses.json`) 자체의 재작성 — 별도 작업
- 두 번째 코드베이스로의 확장 (설계는 막지 않되 구현은 이후)

### 1.5 확인된 사실 (코드로 확인함)

| # | 사실 | 확인 위치 |
|---|---|---|
| F1 | 현재 선별 규칙은 `src/main/java/**/*.java` + `src/main/**/*.py`, 제외는 경로에 `/config/` 포함·파일명 `Application.java` 뿐이다 | `run_s1.py` `RULE`, `run_t.py` `RULE` |
| F2 | 기존 C 조건 실행의 입력은 112파일 14,865행 783,839바이트였다 | `runs/manifest-C.json` |
| F3 | 현재 `src/main` 아래 Java는 155파일 18,127행이다. 패키지별로 `subtask` 36, `service` 19, `model` 16, `ses` 15, `ledger` 13(+`ledger/mcp` 3, `ledger/verify` 2, `ledger/wiring` 1), `llm` 9, `mcp` 8, `tool` 7, `controller` 6, `registry` 4, `web`·`traffic`·`simulation` 각 3, `site` 2, `util`·`obs` 각 1 | `find src/main -name '*.java'` |
| F4 | **`src/main/java/com/wastesim/ses/JangnyangEntityStructure.java`(88행)는 참조 SES ref-v7을 그대로 옮겨 적은 선언이다.** 주석에 "참조 ref-v7에서 옮겨 적었고"라고 쓰여 있고, 루트·엔티티·attrs·decompositions가 리터럴로 들어 있다 | 해당 파일 1–45행 |
| F5 | 기존 112파일 실행의 `file_list`에는 `/ses/`·`/ledger/` 파일이 **하나도 없다**. 그 패키지가 나중에 생겼기 때문이다 | `manifest-C.json.file_list` 집계 |
| F6 | 따라서 **오늘 같은 규칙으로 다시 돌리면 정답지가 입력에 들어간다.** 기존 12건은 그 오염이 없다 | F4 + F5 |
| F7 | `run_t.py`는 루트를 `roots[0]`으로 고른다. 부모로만 등장하는 엔티티가 여럿이면 첫 번째가 임의로 뽑힌다 | `run_t.py` 조립부 |
| F8 | `run_t.py` 조립부는 다음을 **기록 없이 버린다**: dict가 아닌 항목, 엔티티 목록에 없는 parent를 가진 분해, 자기 자신을 자식으로 둔 분해, from/to가 빈 결합, 엔티티 목록에 없는 속성 | `run_t.py` 조립부 |
| F9 | 한 단계라도 실패하면 `continue`로 실행 전체를 버린다. 앞 단계 응답도 저장되지 않는다(성공 시에만 `-stages.json`을 쓴다) | `run_t.py` 실행 루프 |
| F10 | `score_ses.py`는 노드·관계·속성 P/R/F1, 관계 종류 오류, CP-1~5, 근거 없는 노드 수, 반복 간 Jaccard를 낸다. **`couplings`는 채점하지 않는다** | `score_ses.py` `score_one` |
| F11 | 근거 검사는 "엔티티 이름이 `evidence` 키에 있는가"뿐이다. 파일 존재·행 번호·인용 일치는 확인하지 않는다 | `score_ses.py` `score_one`의 `ev_keys` |
| F12 | `active_when`·`attr_spec`은 참조에만 있고 프롬프트에도 채점에도 없다 | `S1-추출절차.md` §2, `reference-ses.json._meta.schema_extensions` |
| F13 | 참조는 51개 엔티티, 결합 6개(각각 `verified`·`mechanism`, 2개는 `active_when`), 임계점 5개를 갖는다 | `reference-ses.json` |
| F14 | 참조의 `verified_against`는 `develop @ 8b3d67f`다. 현재 HEAD는 `12937be`이므로 참조는 이후 커밋과 대조되지 않았다 | `_meta.verified_against`, `git log` |
| F15 | 실행 산출물 파일명은 번호를 늘려 덮어쓰지 않지만, **`manifest-<COND>.json`은 조건마다 한 개라 매번 덮어쓴다** | `run_s1.py`·`run_t.py` manifest 쓰기 |
| F16 | 스냅샷에 파일별 해시도 git rev도 없다. 파일 수·총 행 수·총 바이트만 있다 | `manifest-*.json` 키 |
| F17 | `run_s1.py`는 `REPO`를 절대경로로 박고 `os.chdir(REPO)`한다. `run_t.py`는 자기 위치에서 상대로 찾는다 | 두 파일 상단 |
| F18 | `run_s1.py`는 temperature를 보내지 않고, `run_t.py`는 1로 고정한다. 어느 쪽도 시드를 기록하지 않는다(절차서 §4는 시드 기록을 요구한다) | `ask()`·본문, `S1-추출절차.md` §4 |
| F19 | `runs/DEMO-*.json`은 합성 파일이며 실제 LLM 출력이 아니다 | `README.md` |
| F20 | `src/main` 아래에 `.py` 파일은 없다. 선별 규칙의 `src/main/**/*.py`는 현재 0건을 고른다 | `find src/main -name '*.py'` |

### 1.6 추가 확인이 필요한 사항

- **확인 필요** — `ses`·`ledger`·`subtask`·`llm`·`mcp` 패키지를 제외했을 때 남는 파일 수와 행 수. (규칙을 확정한 뒤 스냅샷 도구로 측정한다)
- **확인 필요** — `ses` 패키지를 제외해도 `service`·`registry`에 SES 용어가 남아 있는지. `SesFieldMapping`·`SimulationConfigFields`가 어디에서 참조되는지 확인해야 한다.
- **확인 필요** — 참조 ref-v7이 `8b3d67f` 이후 커밋(특히 `17be7dd`·`12937be`의 이동시간 0 처리)과 어긋나는지.
- **확인 필요** — 기존 12건(C·Cs·Csr·Cd)과 T 2건의 채점 수치. 이 문서는 그 수치를 인용하지 않는다.
- **확인 필요** — 사용 가능한 모델 목록과 컨텍스트 한도. 현 입력이 19만 토큰대라는 기술이 `run_s1.py` 주석에 있으나 측정 근거는 이 문서에서 확인하지 않았다.

---

## 2. 후보 구현 방식

### 후보 A — 단계별 추출 + 결정적 검증 게이트 (staged extract, gated assembly)

**핵심 아이디어**
추출을 좁은 질문 여러 개로 나누고, 각 단계의 **원문 응답을 먼저 저장한 뒤** 코드가
조립한다. 조립 과정에서 버려지는 항목은 삭제하지 않고 `unresolved`로 옮긴다. 각 단계
사이에 결정적 검증 게이트를 두고, 게이트를 통과하지 못한 항목은 다음 단계 입력에서
빠지되 기록에는 남는다.

**구체적인 처리 과정**

```
0. snapshot   선별 규칙 적용 → 파일별 SHA-256 · 행 수 · git rev 고정
1. index      코드가 기호 색인 생성(파일·클래스·필드·메서드·행 범위)  [LLM 없음]
2. S-a        시간에 따라 변하는 것 + 변화 지점        → stages/a.json
   gate-a     근거 위치 검증(파일 존재·행 범위·인용 일치)
3. S-b        개체 확정 · 동일 개체 병합               → stages/b.json
   gate-b     이름 규칙 · 중복 · 근거 검증
4. S-c        속성 배치                                → stages/c.json
   gate-c     소유 엔티티 존재 · 근거 검증
5. S-d        결합(무엇이 어디로)                      → stages/d.json
   gate-d     from/to 엔티티 존재 · 근거가 상태변경 지점인가
6. S-e        분해 축(aspect/spec/multi)               → stages/e.json
   gate-e     참조 무결성 · 자기참조 · 순환 · 고립
7. S-f        활성 조건(결합·분해가 언제 켜지는가)     → stages/f.json
   gate-f     조건이 가리키는 기호가 코드에 있는가
8. assemble   결정적 조립 + 루트 후보 산출             [LLM 없음]
9. validate   전체 검증 → validation.json
10. report
```

각 단계는 `stages/<단계>.json`을 쓰고 완료 표시를 남긴다. 재실행 시 이미 완료된 단계는
건너뛴다(`--resume <run_id>`). 실패해도 앞 단계는 남는다.

**LLM과 결정적 코드의 역할 분담**

| 하는 일 | 주체 |
|---|---|
| 파일 선별·해시·행 수 | 코드 |
| 기호 색인 | 코드 |
| 무엇이 개체인가 / 속성인가 / 어떤 분해인가 / 무엇이 흐르는가 | LLM |
| 활성 조건의 **의미** 후보 | LLM |
| 근거 파일 존재·행 범위·인용 일치 | 코드 |
| ID 참조 무결성·순환·고립·도달 가능성 | 코드 |
| 루트 후보 열거 | 코드 |
| 루트 확정 | 규칙 통과 시 코드, 아니면 **사람** |
| 조립·병합·중복 제거 | 코드 |
| 폐기 대신 unresolved 이관 | 코드 |

**필요한 변경사항**
- 신규 패키지 `sesx/` (snapshot·index·llm·stages·assemble·evidence·structure·semantics·report)
- 신규 CLI `extract.py`
- `score_ses.py`에 결합·활성 조건 지표 추가(기존 지표는 그대로)
- `run_s1.py`·`run_t.py`는 **동결**. 기존 12건과의 비교를 보존한다

**장점**
- 단계마다 실패가 격리된다. F9(전량 폐기)가 사라진다
- 조립이 결정적이므로 스키마 위반이 구조적으로 불가능하다
- 게이트가 "근거 없는 항목"을 다음 단계로 전파시키지 않는다
- 단계별 토큰·시간·통과율이 그대로 측정 단위가 된다

**구체적인 실패 지점**
- 단계 수만큼 전체 코드를 다시 보낸다 → 비용이 선형으로 는다. 7단계면 입력이 7배다
- 앞 단계의 실수가 뒤 단계를 고정한다. S-b가 개체를 잘못 합치면 S-c~f가 전부 그 위에서 돈다
- 게이트가 과하면 정상 항목까지 unresolved로 가서 재현율이 떨어진다
- 단계 간 이름 표기 흔들림(같은 개체를 다르게 부름)이 참조 무결성 실패로 나타난다

**이 후보가 유효하지 않음을 보여주는 시험**
`unresolved` 비율을 본다. 동일 입력 5회에서 **게이트가 버린 항목 중 사람이 보기에 정당한
항목이 30%를 넘으면** 게이트가 추출을 망가뜨리는 것이다. 또는 단계 수를 4→7로 늘렸을 때
근거 검증 통과율이 오르지 않고 비용만 늘면, 분해의 이득이 없다는 증거다.

---

### 후보 B — 단일 추출 + 검증 피드백 수리 루프 (single-shot, validate-and-repair)

**핵심 아이디어**
기존 `run_s1.py`처럼 한 번에 전체 SES를 받고, 결정적 검증기를 돌린 뒤 **실패 항목만
지목해 다시 묻는다**. 수리는 최대 N회, 매 회차의 입력·응답을 남긴다.

**구체적인 처리 과정**

```
0. snapshot
1. extract    전체 SES 한 번에 요청           → rounds/0.json
2. validate   스키마·근거·구조·의미 검사
3. repair     실패 항목 목록 + 해당 코드 발췌만 붙여 재요청 → rounds/k.json
              (전체 코드를 다시 보내지 않는다)
4. 2~3 반복 (최대 3회 또는 실패 0)
5. 남은 실패 → unresolved
```

**역할 분담**
검증·발췌·수렴 판정은 전부 코드. LLM은 최초 추출과 지목된 자리의 수정만 한다.

**필요한 변경사항**
- `sesx/` 중 snapshot·evidence·structure·semantics·report는 후보 A와 공유
- `sesx/repair.py` 신규
- `run_s1.py` 동결, `extract.py --arm S` 로 재현

**장점**
- 비용이 가장 싸다. 전체 코드를 한 번만 보내고 수리는 발췌만 보낸다
- 전역 일관성이 좋다. 모델이 트리 전체를 한 시야에서 만든다
- 수리 루프가 근거 검증 실패를 직접 겨냥한다

**구체적인 실패 지점**
- 출력 길이 한계에 걸린다. 51노드 규모 트리 + 근거 + 인용문은 `max_tokens` 16,000을 넘길 수 있다 (**확인 필요**)
- 수리가 다른 자리를 망가뜨린다(회귀). 회차마다 전량 재검증이 필요하다
- 수리 루프가 진동한다. A를 고치면 B가 깨지고 되돌아온다
- 스키마 위반이 여전히 가능하다. `decompositions` 위치를 틀리는 실패가 관측된 적이 있다(`run_s1.py` 주석의 C↔Cs 조건 구분이 그 대응이다)

**이 후보가 유효하지 않음을 보여주는 시험**
수리 3회 뒤에도 근거 검증 통과율이 최초 회차 대비 유의하게 오르지 않거나, 회차마다
새 실패가 고친 수만큼 생기면(진동) 이 방식은 수렴하지 않는다. 또는 5회 중 2회 이상에서
출력이 잘리면 규모에서 실패한다.

---

### 후보 C — 코드 우선 후보 색인 + LLM 분류 (deterministic index, LLM as classifier)

**핵심 아이디어**
LLM에게 코드를 통째로 읽히지 않는다. 코드가 먼저 **후보 색인**을 만든다 — 상태 변경
지점(필드 할당·증감·컬렉션 변형), enum 목록, 배열/컬렉션 반복, 조건 분기, 설정 필드.
LLM은 그 색인의 각 항목을 SES 역할(Entity/Attribute/aspect/spec/multi/coupling/조건)로
**분류**하고 이름만 붙인다.

**구체적인 처리 과정**

```
0. snapshot
1. index      Java 파서(tree-sitter 또는 JavaParser)로
              - 필드 선언 · 할당/증감 위치
              - enum 선언과 상수 목록          → spec 후보
              - 배열/List 필드와 크기 결정 지점 → multi 후보
              - if/switch가 읽는 설정 필드      → 활성 조건 후보
              - 메서드 경계와 호출 관계
2. classify   색인 항목 단위로 LLM에 분류 요청 (묶음당 수십 항목, 코드 발췌 동봉)
3. name       엔티티 이름 부여와 병합
4. assemble   결정적 조립
5. validate / report
```

**역할 분담**
근거는 파서가 만들므로 **근거 위조가 원천적으로 불가능하다.** LLM은 이름과 분류만 한다.

**필요한 변경사항**
- Java 파서 의존성 추가 (tree-sitter-java 또는 별도 JVM 도구)
- `sesx/index.py`가 가장 큰 신규 구현
- 프롬프트 전면 재작성. 기존 12건과 비교 불가

**장점**
- 근거 검증 통과율이 설계상 100%에 가깝다
- 입력 토큰이 가장 적다. 발췌만 보낸다
- 색인이 재사용된다. 두 번째 코드베이스로의 확장이 쉽다
- 활성 조건 후보를 코드가 직접 준다 — "모르는 조건을 항상 활성으로 처리"할 유인이 사라진다

**구체적인 실패 지점**
- 파서가 못 보는 개체를 영영 못 본다. 참조의 `민원 판정`·`적재초과 판정`은 클래스가 아니라
  엔진 안의 판정 로직이다(`SimulationEngine:460` 등). 색인이 "필드 증감"을 잡아야 겨우 후보가 된다
- 색인 항목이 수천 개가 되어 분류 비용이 오히려 커진다
- 색인 규칙 자체가 도메인에 묶이기 쉽다. "어떤 증감을 개체로 볼지"를 사람이 정하면 B 조건(순환)에 가까워진다
- 구현량이 가장 크다. 최소 프로토타입에 넣기 어렵다

**이 후보가 유효하지 않음을 보여주는 시험**
색인만으로 참조의 엔티티 51개 중 **몇 개가 후보에 들어오는지** 센다(리콜 상한 측정,
LLM 없이 가능). 상한이 60% 미만이면 분류를 아무리 잘해도 그 이상 갈 수 없다.
이 시험은 LLM 비용 없이 하루 안에 돌릴 수 있으므로 **후보 C의 채택 여부는 이 측정으로 결정한다.**

---

## 3. 최종 권고 방식

### 선택한 방식

**후보 A를 기본 골격으로 채택한다.** 단, 다음 둘을 붙인다.

1. **후보 B를 두 번째 팔(arm)로 남긴다.** 문제 10(단일 vs 단계별 비교)이 요구 사항이므로
   두 방식이 **같은 검증기·같은 데이터 계약·같은 스냅샷**을 공유하는 하나의 CLI 아래
   들어가야 한다. 비교의 공정성은 여기서 나온다.
2. **후보 C의 색인은 "검증 보조"로만 먼저 넣는다.** 근거 검증(파일·행 범위·기호 존재)에
   어차피 기호 색인이 필요하다. 추출 경로로 승격할지는 §10의 리콜 상한 측정 결과로 정한다.

즉 **단일 파이프라인, 두 추출 팔(S/T), 공용 검증기, 색인은 검증용으로 먼저.**

### 선택 근거

- 제약조건이 "근거 없는 항목은 `unresolved`", "조용히 삭제 금지", "실패 후 재개"를 명시한다.
  단계별 산출물을 남기는 구조가 아니면 이 셋을 동시에 만족시킬 수 없다. F9가 그 반례다.
- "여러 루트가 발견되면 첫 번째를 임의로 선택하면 안 된다"는 F7을 직접 고치라는 뜻이다.
  조립이 결정적 코드에 있어야 루트 후보를 전부 보존하고 규칙을 적용할 수 있다.
- 비교 실험이 목적이므로 두 팔을 같은 계약 아래 두는 것이 가장 싸게 공정성을 얻는 길이다.
- 후보 C는 이득이 크지만 리콜 상한이 미지수다. 상한을 재기 전에 투자하면 되돌리기 어렵다.

### 다른 방식을 선택하지 않은 이유

- **B 단독** — 실패가 전량 폐기로 이어지고, 단계별 통과율을 볼 수 없어 "어디서 틀렸는가"를
  진단할 수 없다. 다만 비용이 싸므로 비교 팔로는 반드시 남긴다.
- **C 단독** — 참조의 판정 계열 엔티티가 클래스가 아니라는 사실(F13 근거 참조) 때문에
  리콜 상한이 낮을 위험이 실재한다. 상한 측정 전에는 채택할 수 없다.

### 선택을 뒤집을 수 있는 증거

| 증거 | 뒤집히는 결정 |
|---|---|
| C의 색인 리콜 상한 ≥ 85% | C를 주 추출 경로로 승격, A는 분류 단계 조립기로 축소 |
| A의 단계별 게이트가 정당한 항목을 30% 이상 버림 | 게이트를 경고로 강등, B 쪽으로 이동 |
| 단계 수를 늘려도 근거 통과율·구조 통과율이 개선되지 않음 | A의 분해 이득 없음 → B 채택 |
| 단일 추출에서 출력 잘림이 5회 중 2회 이상 | B 팔을 규모 한계로 기각(비교는 유지하되 해석에 명시) |
| 단계 간 이름 흔들림이 참조 무결성 실패의 주원인 | A에 "이름 사전 고정" 단계를 추가하거나 C의 기호 앵커로 전환 |

---

## 4. 출력 데이터 계약

`schema_version: "ses-extract/1.0"`. 모든 ID는 실행 안에서 유일한 문자열이다.
**이름이 아니라 ID로 참조한다** — 이름 흔들림을 참조 무결성 문제와 분리하기 위해서다.

### 4.1 파일 배치

```
exp/<run_id>/
  snapshot.json
  run.json            추출 실행 메타데이터
  stages/a.json ... f.json
  ses.json            확정 구조 (Entity/Attribute/Decomposition/Coupling/Activation)
  evidence.json       근거 레코드 (ses.json에서 ID로 참조)
  unresolved.json
  validation.json
  report.md
```

`exp/` 는 신규 디렉터리다. 기존 `runs/`는 건드리지 않는다(F19, 제약조건 "덮어쓰기 금지").

### 4.2 snapshot.json

```json
{
  "schema_version": "ses-extract/1.0",
  "snapshot_id": "snap-2026-09-14-01",
  "git": { "rev": "12937be", "branch": "develop", "dirty": false },
  "selection_rule": {
    "rule_id": "R2",
    "include_globs": ["src/main/java/**/*.java", "src/main/**/*.py"],
    "exclude_globs": [
      "**/config/**",
      "**/*Application.java",
      "**/com/wastesim/ses/**",
      "**/com/wastesim/ledger/**",
      "**/com/wastesim/subtask/**",
      "**/com/wastesim/llm/**",
      "**/com/wastesim/mcp/**",
      "**/com/wastesim/controller/**",
      "**/com/wastesim/web/**",
      "**/com/wastesim/obs/**"
    ],
    "rationale_class": "도메인 지식 없이 적을 수 있는 규칙만 사용. 각 제외는 '실행 지원 소프트웨어' 근거를 rule_notes에 남긴다",
    "rule_notes": {
      "com/wastesim/ses/**": "이 패키지는 SES 구조 선언 자체를 담는다(JangnyangEntityStructure.java). 추출 입력에 정답이 들어간다",
      "com/wastesim/ledger/**": "결정기록. 시뮬레이션 대상이 아니라 구성 절차의 기록 장치",
      "com/wastesim/subtask/**": "문항 수집 계층",
      "com/wastesim/llm/**": "모델 호출 계층",
      "com/wastesim/mcp/**": "도구 노출 계층",
      "com/wastesim/controller/**,web/**,obs/**": "HTTP·오류·관측 계층"
    }
  },
  "files": [
    { "path": "src/main/java/com/wastesim/simulation/SimulationEngine.java",
      "sha256": "…", "lines": 0, "bytes": 0 }
  ],
  "totals": { "files": 0, "lines": 0, "bytes": 0 },
  "excluded_sample": [
    { "path": "src/main/java/com/wastesim/ses/JangnyangEntityStructure.java",
      "matched_rule": "**/com/wastesim/ses/**" }
  ]
}
```

`lines`·`bytes`·`sha256`는 실측값으로 채운다. 위 0은 자리표시다.

### 4.3 ses.json

```json
{
  "schema_version": "ses-extract/1.0",
  "run_id": "T-gpt-4.1-mini-2026-09-14-01",
  "snapshot_id": "snap-2026-09-14-01",

  "root": { "entity_id": null, "status": "unresolved",
            "candidates": ["E001", "E017"],
            "reason": "부모로만 등장하는 엔티티가 2개다. 규칙으로 좁히지 못했다" },

  "entities": [
    { "id": "E001", "name": "수거지점", "kind": "entity",
      "why_entity": "시간에 따라 적재량이 바뀐다",
      "evidence_ids": ["EV012", "EV013"],
      "confidence": 0.82 }
  ],

  "attributes": [
    { "id": "A004", "entity_id": "E001", "name": "적재량",
      "value_kind": "unresolved",
      "unit": null, "default": null, "range": null,
      "declared_at_evidence_id": "EV020",
      "described_entity_rationale": "선언은 엔진의 배열이지만 값이 서술하는 것은 수거지점이다",
      "evidence_ids": ["EV020"], "confidence": 0.74 }
  ],

  "decompositions": [
    { "id": "D002", "parent_id": "E005", "kind": "spec",
      "axis_name": "이동시간 방식 축",
      "children_ids": ["E031", "E032", "E033"],
      "selector": { "symbol": "travelTimeMode", "evidence_id": "EV041" },
      "evidence_ids": ["EV041"], "confidence": 0.66 }
  ],

  "couplings": [
    { "id": "C003",
      "from": { "entity_id": "E009", "port": "혼잡계수" },
      "to":   { "entity_id": "E005", "port": "이동시간" },
      "transfer_kind": "value",
      "mechanism": "TrafficProfile.weightAt(...) 결과가 다음 지점 도착 시각 계산에 들어간다",
      "activation_id": "AC001",
      "evidence_ids": ["EV050", "EV051"], "confidence": 0.71 }
  ],

  "activations": [
    { "id": "AC001",
      "applies_to": { "kind": "coupling", "target_id": "C003" },
      "status": "asserted",
      "expression": "trafficMode == APPLY",
      "selector_symbol": "trafficMode",
      "otherwise": "trafficProfile 가 null 이므로 결합이 성립하지 않는다",
      "evidence_ids": ["EV052"], "confidence": 0.63 }
  ]
}
```

**활성 조건의 `status`는 세 값만 갖는다.**
`asserted`(코드 근거로 조건을 특정함) · `unconditional_verified`(조건 분기가 없음을
코드로 확인함) · `unresolved`(모름). **`unresolved`를 "항상 활성"으로 바꾸는 경로는
구현하지 않는다.** 검증기는 `status`가 없는 항목을 스키마 오류로 잡는다.

### 4.4 evidence.json

```json
{
  "schema_version": "ses-extract/1.0",
  "records": [
    { "id": "EV012",
      "file": "src/main/java/com/wastesim/simulation/SimulationEngine.java",
      "file_sha256": "…",
      "line_start": 457, "line_end": 459,
      "symbol": "SimulationEngine.step",
      "quote": "fill[building][type] += amount * WasteType.fraction",
      "claim_kind": "state_change",
      "verification": {
        "file_exists": null, "line_in_range": null,
        "quote_match": null, "symbol_found": null,
        "checked_at": null, "verdict": "unchecked"
      } }
  ]
}
```

- `claim_kind` ∈ `state_change` · `declaration` · `branch` · `construction` · `call` · `enum_member`
- `quote`는 **원문 그대로**여야 한다. 검증기가 정규화(공백 축약) 후 부분 문자열 일치로 확인한다
- `verdict` ∈ `unchecked` · `pass` · `fail_file` · `fail_range` · `fail_quote` · `fail_symbol`

### 4.5 unresolved.json

```json
{
  "schema_version": "ses-extract/1.0",
  "items": [
    { "id": "U001",
      "origin_stage": "e",
      "origin_raw": { "parent": "수거 경로", "kind": "spec", "children": ["수거 경로"] },
      "reason_code": "self_reference",
      "reason": "자기 자신을 자식으로 둔 분해다",
      "disposition": "held",
      "reviewer_note": null }
  ]
}
```

`reason_code` 최소 집합: `no_evidence` · `evidence_failed` · `unknown_reference` ·
`self_reference` · `cycle` · `orphan` · `duplicate_name` · `multi_root` ·
`kind_conflict` · `unknown_activation` · `schema_violation` · `parse_error`.
`disposition` ∈ `held`(보류) · `rejected_by_reviewer` · `promoted_by_reviewer`.
**어떤 코드 경로도 항목을 파일에서 지우지 않는다.**

### 4.6 validation.json

```json
{
  "schema_version": "ses-extract/1.0",
  "run_id": "…", "snapshot_id": "…",
  "checks": [
    { "id": "SCHEMA-01", "layer": "schema", "status": "pass", "failures": [] },
    { "id": "EVID-02", "layer": "evidence", "status": "fail",
      "summary": "행 범위 밖 3건", "failures": ["EV031", "EV044", "EV077"] },
    { "id": "STRUCT-04", "layer": "structure", "status": "fail",
      "summary": "루트 후보 2개", "failures": ["E001", "E017"] },
    { "id": "SEM-02", "layer": "semantics", "status": "warn",
      "summary": "spec 축에 선택자 기호가 없는 축 2개", "failures": ["D007", "D011"] }
  ],
  "totals": { "pass": 0, "warn": 0, "fail": 0 },
  "gate": { "blocking_failures": 2, "ready_for_review": false }
}
```

### 4.7 run.json (추출 실행 메타데이터)

```json
{
  "schema_version": "ses-extract/1.0",
  "run_id": "…", "arm": "T2", "snapshot_id": "…",
  "started_at": "…", "finished_at": "…",
  "model": { "name": "gpt-4.1-mini", "temperature": 1, "top_p": null,
             "max_tokens": 16000, "response_format": "json_object",
             "seed": null, "api": "chat.completions" },
  "prompts": [
    { "stage": "a", "prompt_sha256": "…", "prompt_chars": 0,
      "source": "sesx/prompts/T2/a.md" }
  ],
  "usage": [ { "stage": "a", "prompt_tokens": 0, "completion_tokens": 0,
               "latency_s": 0.0, "retries": 0, "http_errors": [] } ],
  "stage_status": { "a": "done", "b": "done", "c": "failed", "d": "skipped" },
  "resumed_from": null,
  "code_version": { "sesx_sha256": "…", "git_rev": "12937be" }
}
```

응답 원문은 `stages/<단계>.json`의 `raw` 필드에 **가공 전 문자열 그대로** 저장한다.

---

## 5. 단계별 구현계획

각 단계는 앞 단계 없이 시작할 수 없다. 단계마다 테스트가 통과해야 다음으로 간다.

### P0 — 스냅샷과 선별 규칙 (LLM 없음)

- **구현 내용** 선별 규칙을 파일로 분리하고, 파일별 SHA-256·행 수·git rev를 고정한다.
  제외된 파일과 그 사유 규칙을 함께 기록한다.
- **구성요소** 신규 `sesx/snapshot.py`, 신규 `sesx/rules/R2.json`, 신규 `extract.py snapshot`
- **입력** 저장소 작업트리
- **출력** `exp/<run_id>/snapshot.json`
- **완료조건** ① `ses`·`ledger`·`subtask`·`llm`·`mcp`·`controller`·`web`·`obs` 패키지가
  `files`에 하나도 없다 ② `excluded_sample`에 `JangnyangEntityStructure.java`가 있다
  ③ 같은 작업트리에서 두 번 돌리면 `snapshot.json`이 바이트 단위로 같다(생성 시각 제외)
- **자동 테스트**
  - `test_snapshot_excludes_ses_package` — 선별 결과에 `/ses/` 경로가 0건
  - `test_snapshot_no_answer_leak` — 선별된 어떤 파일에도 참조 루트 이름 문자열이 없다
  - `test_snapshot_deterministic` — 두 번 실행의 파일 목록·해시가 동일
  - `test_snapshot_hash_changes_on_edit` — 임시 파일 1바이트 수정 시 해시가 바뀐다
- **실패 시 처리** 누출 검사가 걸리면 스냅샷을 쓰지 않고 중단한다. 제외 규칙을 고치고 다시 돌린다.

### P1 — 데이터 계약과 스키마 검증기 (LLM 없음)

- **구현 내용** §4의 JSON Schema를 파일로 쓰고, 로더·검증기를 만든다.
- **구성요소** 신규 `sesx/schema/*.schema.json`, `sesx/contract.py`
- **입력** §4
- **출력** 스키마 파일 + `validate_schema(doc) -> [failure]`
- **완료조건** 참조 SES를 계약 형식으로 변환한 고정본이 스키마를 통과한다
- **자동 테스트**
  - 필수 키 누락·`status` 누락·알 수 없는 `reason_code`·중복 ID를 각각 잡는다
  - `test_activation_requires_status` — `status` 없는 활성 조건은 오류
  - `test_no_always_active_default` — 계약에 "기본 활성" 값이 존재하지 않는다
- **실패 시 처리** 스키마와 §4가 어긋나면 §4를 고친다. 코드가 스키마를 우회하지 않는다.

### P2 — 기호 색인과 근거 검증기 (LLM 없음)

- **구현 내용** 파일·행 범위·기호·인용 일치를 확인하는 검증기. 기호 색인은 1차로
  정규식 수준(클래스·메서드·필드 선언과 행 범위)으로 시작한다.
- **구성요소** 신규 `sesx/index.py`, `sesx/evidence.py`
- **입력** `snapshot.json` + `evidence.json`
- **출력** 각 레코드의 `verification` 채움
- **완료조건** 참조 SES의 evidence 항목(F13)을 변환해 돌렸을 때, 통과/실패가
  사람이 확인한 결과와 일치한다
- **자동 테스트**
  - 존재하지 않는 파일 → `fail_file`
  - 파일 행 수를 넘는 행 번호 → `fail_range`
  - 인용문이 해당 행 범위에 없음 → `fail_quote`
  - 공백만 다른 인용문 → `pass` (정규화 확인)
  - 스냅샷 해시와 현재 파일이 다르면 검증 자체를 거부한다
- **실패 시 처리** 스냅샷 불일치는 오류로 띄우고 진행하지 않는다.

### P3 — 구조 검증기 (LLM 없음)

- **구현 내용** 참조 무결성·루트·순환·고립·도달 가능성.
- **구성요소** 신규 `sesx/structure.py`
- **완료조건** §6.4의 검사들이 각각 합성 반례에서 실패를 보고한다
- **자동 테스트** §6.4 표의 검사별 반례 각 1건 + 참조 변환본은 전부 통과
- **실패 시 처리** 검사 자체의 버그가 의심되면 참조 변환본으로 회귀 확인.

### P4 — 결정적 조립기 + unresolved 이관 (LLM 없음)

- **구현 내용** 단계 산출물 → `ses.json`. **버리는 대신 옮긴다.** 루트 후보는 전부 보존.
- **구성요소** 신규 `sesx/assemble.py`
- **완료조건** F8의 다섯 가지 폐기 경로가 전부 `unresolved.json` 항목으로 나타난다
- **자동 테스트**
  - `test_self_reference_goes_to_unresolved`
  - `test_unknown_parent_goes_to_unresolved`
  - `test_multi_root_sets_status_unresolved` — 루트 후보 2개면 `root.entity_id`가 null
  - `test_nothing_silently_dropped` — 입력 항목 수 == ses 항목 수 + unresolved 항목 수
- **실패 시 처리** 보존 불변식이 깨지면 조립을 중단한다.

### P5 — 단계별 추출 팔(T2) 실행기

- **구현 내용** §2 후보 A의 단계 a~f. 단계별 저장·재개·재시도.
- **구성요소** 신규 `sesx/llm.py`, `sesx/stages.py`, `extract.py run --arm T2`
- **완료조건** ① 중간에 죽여도 `--resume`으로 남은 단계만 다시 돈다
  ② 모든 단계의 프롬프트 해시와 원문 응답이 남는다
- **자동 테스트**
  - 가짜 LLM(고정 응답)으로 전 단계 통과 → `ses.json` 생성
  - 단계 c에서 예외를 주입 → a·b 산출물이 남고 `stage_status.c == "failed"`
  - 재개 실행이 a·b를 다시 호출하지 않는다(호출 카운터 확인)
- **실패 시 처리** 429는 대기 후 재시도(기존 `run_t.py`의 처리를 옮긴다), 그 외 HTTP 오류는
  단계 실패로 기록하고 종료.

### P6 — 단일 추출 팔(S2) 실행기 + 수리 루프

- **구현 내용** 후보 B. 최초 추출 1회 + 검증 실패 지목 수리 최대 3회.
- **구성요소** 신규 `sesx/repair.py`, `extract.py run --arm S2`
- **완료조건** 회차별 입력·응답·검증 결과가 전부 남고, 수렴/비수렴이 판정된다
- **자동 테스트**
  - 가짜 LLM으로 1회차 실패 → 2회차 수정 → 검증 통과 경로
  - 진동 감지: 같은 실패 집합이 두 회차 연속이면 루프를 끊고 `unresolved`로 보낸다
- **실패 시 처리** 출력 잘림은 `parse_error`로 기록하고 회차를 소비한다.

### P7 — 의미 검증 · 채점 확장 · 보고

- **구현 내용** §6.5·§6.6의 규칙 검사, `score_ses.py`에 결합·활성 조건 지표 추가, `report.md`.
- **구성요소** 신규 `sesx/semantics.py`, `sesx/report.py`, 수정 `score_ses.py`
- **완료조건** 기존 `score_ses.py --selftest` 6건이 그대로 통과하고 신규 자체검증이 추가된다
- **자동 테스트** 기존 6건 + 결합 채점 3건(완전일치·포트만 다름·방향 반대) + 활성 조건 2건
- **실패 시 처리** 기존 6건 중 하나라도 깨지면 확장을 되돌린다. 기존 채점을 바꾸지 않는다.

---

## 6. 검증계획

여섯 층을 분리한다. 층마다 실패가 다른 것을 뜻한다.

### 6.1 JSON 스키마 검증 (기계, 차단)

| ID | 내용 |
|---|---|
| SCHEMA-01 | 필수 키·타입·열거값 |
| SCHEMA-02 | ID 유일성 (엔티티·속성·분해·결합·활성·근거) |
| SCHEMA-03 | 활성 조건 `status` 존재 및 세 값 중 하나 |
| SCHEMA-04 | 근거 레코드에 `file`·`line_start`·`quote` 존재 |
| SCHEMA-05 | 계약 밖 최상위 키 없음 |

### 6.2 코드 근거 위치 검증 (기계, 차단)

| ID | 내용 | 실패 코드 |
|---|---|---|
| EVID-01 | 파일이 스냅샷에 있는가 | `fail_file` |
| EVID-02 | `line_start ≤ line_end ≤ 파일 행 수` | `fail_range` |
| EVID-03 | 인용문이 해당 행 범위 안에 있는가(공백 정규화 후) | `fail_quote` |
| EVID-04 | `symbol`이 색인에 있고 그 행 범위와 겹치는가 | `fail_symbol` |
| EVID-05 | 파일 SHA-256이 스냅샷과 일치하는가 | 검증 거부 |
| EVID-06 | `claim_kind`가 `state_change`인 근거가 실제로 할당·증감·컬렉션 변형 줄을 가리키는가 | 경고 |

EVID-06만 경고다. 정적 판정이 불완전하기 때문이다.

### 6.3 SES 참조 무결성 검증 (기계, 차단)

| ID | 내용 |
|---|---|
| REF-01 | 모든 `entity_id`·`parent_id`·`children_ids`가 존재하는 엔티티를 가리킨다 |
| REF-02 | 모든 `evidence_ids`가 존재하는 근거를 가리킨다 |
| REF-03 | 결합의 `from/to` 엔티티가 존재한다 |
| REF-04 | `activation_id`가 존재하는 활성 조건을 가리킨다 |
| REF-05 | 근거가 0개인 항목이 없다 (있으면 `unresolved`로 갔어야 한다) |

### 6.4 루트·순환·고립·도달 가능성 (기계, 차단)

| ID | 내용 | 미통과 시 |
|---|---|---|
| STRUCT-01 | 분해 그래프에 순환이 없다 | 순환 경로를 `unresolved`로, `fail` |
| STRUCT-02 | 자기 자신을 자식으로 두지 않는다 | `self_reference` |
| STRUCT-03 | 부모로만 등장하는 엔티티(=루트 후보)를 **전부** 열거한다 | 정보 |
| STRUCT-04 | 루트 후보가 정확히 1개다 | 2개 이상이면 `root.status=unresolved`, `fail`. **첫 번째를 고르지 않는다** |
| STRUCT-05 | 루트에서 모든 엔티티에 도달 가능하다 | 도달 불가 목록을 `orphan`으로 보고 |
| STRUCT-06 | 어떤 엔티티도 두 부모를 갖지 않는다(SES 트리 가정) | 다부모는 `kind_conflict` 경고 |
| STRUCT-07 | 같은 정규화 이름을 가진 엔티티가 둘 이상 없다 | `duplicate_name` |

루트 후보가 2개 이상일 때의 처리 순서: ① 후보를 전부 기록 ② "다른 후보를 자식으로 갖는
후보가 유일하게 존재하는가" 같은 **명시된 규칙**만 적용 ③ 그래도 남으면 사람에게 넘긴다.

### 6.5 ASPECT·SPEC·MULTI 의미 검증 (기계 경고 + 사람 확정)

기계가 확정할 수 있는 것은 적다. 다음은 **경고**로 내고 사람이 판단한다.

| ID | 규칙 | 근거 요구 |
|---|---|---|
| SEM-01 | `spec` 축에는 선택자가 있어야 한다 — enum·설정 필드·분기 조건 중 하나를 `selector.symbol`로 지목 | 지목 없으면 경고 |
| SEM-02 | `spec` 자식들이 코드에서 **배타적**인가 — 같은 실행에서 둘 이상이 동시에 쓰이는 증거가 있으면 `aspect` 의심 | 경고 + 증거 |
| SEM-03 | `multi` 축에는 개수를 정하는 자리가 있어야 한다 — 배열·컬렉션 크기·반복 상한 | 지목 없으면 경고 |
| SEM-04 | `aspect` 자식들이 동시에 존재하는가 — 배타 분기 아래에만 나타나면 `spec` 의심 | 경고 |
| SEM-05 | `spec` 자식이 부모의 속성을 좁히는가(특수화) 아니면 부분을 이루는가(구성) | 사람 |
| SEM-06 | 같은 부모의 두 `spec` 축이 독립인가 — 합치면 축별 선택이 불가능해진다 | 사람 |

SEM-02·04는 "같은 메서드 본문에서 두 자식의 근거 행이 모두 실행되는가"를 근사로 본다.
근사이므로 절대 차단하지 않는다.

### 6.6 Coupling 및 활성 조건 검증

| ID | 내용 | 층 |
|---|---|---|
| CPL-01 | `from/to` 엔티티 존재 | 차단 |
| CPL-02 | 근거가 **상태 변경 지점**을 가리키는가(참조 보유·타입 사용은 결합이 아니다) | 경고 |
| CPL-03 | 포트 이름이 엔티티의 속성 또는 이벤트 이름과 대응하는가 | 경고 |
| CPL-04 | 양방향 중복(A→B와 B→A가 같은 근거) | 경고 |
| ACT-01 | `status`가 `unresolved`가 아니면 `expression`과 `selector_symbol`이 있다 | 차단 |
| ACT-02 | `selector_symbol`이 코드에 실재한다 | 차단 |
| ACT-03 | `unconditional_verified`는 해당 결합 근거 행이 조건 분기 안에 없음을 색인으로 확인했을 때만 | 차단 |

**채점 지표 추가** (`score_ses.py`)
- 결합: `(from_entity, to_entity)` 쌍 P/R/F1 — 포트 무시 판과 포트 포함 판을 따로 낸다
- 활성 조건: 참조가 `active_when`을 가진 결합에 대해 `selector_symbol` 일치율
- **활성 조건 과잉 확정률** — 참조가 조건부인 결합을 추출이 무조건으로 적은 비율.
  이 지표는 낮을수록 좋고, `unresolved`로 남긴 것은 과잉으로 세지 않는다

### 6.7 전문가 검토가 필요한 항목

기계가 판정하지 않고 사람에게 넘기는 것을 명시한다.

1. 루트 확정 (후보 2개 이상)
2. SEM-05·SEM-06 (특수화 vs 구성, 축 독립성)
3. 개체인가 소프트웨어 클래스인가의 경계 사례
4. 속성의 소유 엔티티 (선언 위치 ≠ 서술 대상인 경우)
5. 포트 이름의 타당성
6. 단위·기본값·범위 — **LLM이 생성하지 않는다.** 코드에 리터럴이 있으면 근거와 함께 후보로만 제시
7. `unresolved` 항목의 `disposition` 결정

---

## 7. 실험계획

### 7.1 실험 단위

**한 실행(run)** = 하나의 스냅샷 + 하나의 팔 + 하나의 모델 + 하나의 반복 인덱스.
비교는 **팔** 단위로 한다.

| 팔 | 내용 |
|---|---|
| S2 | 단일 추출 + 수리 루프 (후보 B) |
| S2-nr | 단일 추출, 수리 없음 (수리 루프의 효과를 분리) |
| T2 | 단계별 추출 (후보 A) |
| T2-min | 단계 a·b·c·e만 (분해 깊이의 효과를 분리) |

기존 `run_s1.py`·`run_t.py`의 12+2건은 **동결된 과거 기록**으로 남긴다. 입력 스냅샷이
다르므로(F3·F6) 신규 실행과 같은 표에 넣지 않는다.

### 7.2 반복 횟수의 목적

반복은 성능 비교가 아니라 **흔들림 측정**이다. 팔당 5회. 5회는 평균 비교의 근거로는
약하고, 분산이 큰지 작은지를 보는 데는 쓸 수 있다. 팔 간 평균 차이를 유의성으로
주장하지 않는다.

### 7.3 평가 지표

**참조 무관 (1차)**
- 근거 검증 통과율 = `pass` 근거 / 전체 근거
- 구조 검증 차단 실패 건수
- `unresolved` 비율 = unresolved 항목 / (ses 항목 + unresolved 항목)
- 반복 간 Jaccard (노드·관계·결합 각각)
- 완주율 = 검증까지 도달한 실행 / 시도한 실행

**참조 대비 (2차, 1차를 통과한 실행에 대해서만)**
- 노드·관계·속성 P/R/F1 (기존 `score_ses.py`)
- 관계 종류 오류 건수
- CP-1~5 통과
- 결합 P/R/F1 (신규)
- 활성 조건 일치율·과잉 확정률 (신규)

### 7.4 정확도 평가 방법

1. 이름 정규화 → 별칭 사전(`aliases.json`, 채점자 전용) → 미매칭 보고.
   **별칭은 추출기에 절대 주지 않는다.**
2. 새 별칭을 추가하면 그 사실과 시점을 기록하고, 별칭 추가 전/후 수치를 **둘 다** 낸다.
3. 참조가 틀렸다고 판단되면 참조를 고치되 버전을 올리고(ref-v8), 이전 버전 채점 결과를
   지우지 않는다.

### 7.5 반복 안정성

팔별로 5회의 쌍별 Jaccard 평균과 **최소값**을 함께 낸다. 평균만 보면 한 번의 붕괴가
가려진다. 노드 Jaccard 최소값이 0.5 미만인 팔은 "불안정"으로 표시한다.

### 7.6 전문가 수정 시간

검토자가 `unresolved` 항목과 경고를 처리해 "검토 완료" 상태로 만드는 데 걸린 시간을
항목 수와 함께 기록한다. 측정 방법: 검토 세션의 시작·종료 시각과 처리한 항목 수.
**확인 필요** — 검토자를 몇 명 확보할 수 있는지. 1명이면 순서 효과가 있으므로 팔 제시
순서를 무작위화하고 그 사실을 보고한다.

### 7.7 비용과 입력량

실행별로 기록: 단계별 prompt/completion 토큰, 총 토큰, 벽시계 시간, 재시도 횟수,
입력 파일 수·행 수. 팔 비교 시 **항목 하나당 토큰**(총 토큰 / 검증 통과 항목 수)을 함께 낸다.
총 토큰만 보면 단계별 방식이 무조건 비싸 보인다.

### 7.8 오염 방지

| 위험 | 대응 |
|---|---|
| 정답지가 입력에 섞임 (F4·F6) | 선별 규칙에서 `ses` 패키지 제외 + 스냅샷 시 누출 검사 |
| 참조 노드 이름이 코드 주석·리소스에 있음 | 스냅샷 단계에서 참조의 엔티티 이름 51개를 선별 파일 전문에서 검색해 히트를 보고. 히트가 있으면 그 파일을 제외하거나 실험에 명시 |
| 별칭 사전 유출 | `aliases.json`은 채점 경로에서만 읽는다. 추출 경로 코드에 import 금지 — 테스트로 강제 |
| 프롬프트에 도메인 힌트 | 프롬프트 해시를 기록하고, 변경 시 조건을 새로 만든다(기존 조건을 고치지 않는다) |
| 기존 결과 덮어쓰기 (F15) | `exp/<run_id>/`는 생성 시 이미 있으면 실패. manifest는 run_id 안에 둔다 |
| 사람이 파일을 골라줌(B 조건) | 선별은 규칙 파일로만. 규칙 변경은 커밋으로 남는다 |

### 7.9 성공 기준

파일럿(팔당 5회, 모델 1종) 기준.

1. 완주율 ≥ 80%
2. 근거 검증 통과율 ≥ 70% (한 팔 이상에서)
3. 구조 검증 차단 실패 0건인 실행이 팔당 1건 이상
4. 노드 Jaccard 평균 ≥ 0.5
5. `unresolved` 항목이 검토자가 30분 안에 처리 가능한 규모(대략 50건 이하)

이 다섯을 만족하면 "검토 가능한 SES 초안을 안정적으로 뽑는다"는 목표에 도달한 것으로 본다.
**팔 간 우열은 파일럿에서 결론짓지 않는다.** 5회 × 2팔은 우열의 통계적 증거가 되지 못한다.

### 7.10 재설계 기준

- 근거 검증 통과율이 두 팔 모두 50% 미만 → 프롬프트가 아니라 **근거 요구 형식**이 문제다.
  후보 C(색인 기반)로 전환을 검토한다
- `unresolved`가 전체의 50%를 넘음 → 게이트가 과하다. 차단/경고 경계를 다시 긋는다
- 노드 Jaccard 평균 < 0.3 → 추출 자체가 불안정하다. 온도·모델·입력 크기를 먼저 갈라 본다
- 완주율 < 50% → 규모 문제. 입력 분할 설계로 돌아간다

---

## 8. 최소 프로토타입

### 8.1 가장 먼저 구현할 실행 경로

```
extract.py snapshot --rule R2 --run-id <id>
extract.py run      --arm T2 --run-id <id> --stages a,b,c,e
extract.py validate --run-id <id>
extract.py report   --run-id <id>
```

한 모델, 한 팔(T2), 네 단계(a 상태변화 · b 개체 · c 속성 · e 분해), 1회 실행.

### 8.2 반드시 포함할 기능

1. 선별 규칙 파일화 + `ses`·`ledger`·`subtask`·`llm`·`mcp` 제외 + **누출 검사**
2. 파일별 SHA-256과 git rev
3. 단계별 프롬프트·원문 응답 저장
4. 근거 레코드(파일·행 범위·인용) 요구와 **행 범위·인용 일치 검증**
5. 결정적 조립 + `unresolved` 이관 (아무것도 조용히 버리지 않음)
6. 루트 후보 전수 열거 + 2개 이상이면 `unresolved`
7. 참조 무결성·순환·고립 검사
8. `validation.json`과 `report.md`

### 8.3 제외할 기능

- 결합·활성 조건 추출 (단계 d·f) — 검증기만 먼저 만들고 추출은 다음
- 수리 루프(S2), 단일 추출 팔
- AST 기반 색인 (정규식 색인으로 시작)
- `attr_spec`, 단위·범위 후보
- 매개변수 연결
- 참조 대비 채점 — 프로토타입의 판정은 §8.5로 한다

### 8.4 산출물

`exp/<run_id>/` 아래 `snapshot.json` · `stages/{a,b,c,e}.json` · `ses.json` ·
`evidence.json` · `unresolved.json` · `validation.json` · `report.md` · `run.json`

### 8.5 인수 테스트

| # | 시험 | 기대 |
|---|---|---|
| AT-1 | `snapshot` 실행 | `files`에 `/ses/`·`/ledger/`·`/subtask/`·`/llm/`·`/mcp/` 0건 |
| AT-2 | 누출 검사 | 참조 루트 이름이 선별 파일 어디에도 없다 |
| AT-3 | 단계 c 도중 프로세스 강제 종료 후 `--resume` | a·b를 다시 호출하지 않고 c부터 재개 |
| AT-4 | 근거에 없는 행 번호를 주입 | `fail_range`로 보고되고 해당 항목이 `unresolved`로 간다 |
| AT-5 | 분해에 존재하지 않는 자식 이름 주입 | `unknown_reference`로 `unresolved` 이관, `ses.json`에 없음 |
| AT-6 | 루트 후보 2개인 합성 입력 | `root.entity_id == null`, `candidates` 길이 2 |
| AT-7 | 보존 불변식 | 단계 산출 항목 수 == ses 항목 수 + unresolved 항목 수 |
| AT-8 | 같은 run-id 재실행 | 실패(덮어쓰기 금지) |

### 8.6 인수 기준

AT-1~8 전부 통과 + 실제 1회 실행이 `validation.json`을 생성하고 `report.md`가
① 엔티티 수 ② 근거 통과율 ③ 차단 실패 목록 ④ `unresolved` 건수를 보여준다.
**정확도는 인수 기준에 넣지 않는다.**

---

## 9. 구현 순서

### 9.1 유지 (건드리지 않음)

| 파일 | 이유 |
|---|---|
| `runs/**` 전체 | 기존 12+2건의 기록. 덮어쓰기 금지 |
| `run_s1.py` · `run_t.py` | 과거 조건의 정의 그 자체. 고치면 과거 결과의 의미가 바뀐다 |
| `reference-ses.json` | 별도 작업으로만 개정(ref-v8). 이 계획에서 고치지 않는다 |
| `aliases.json` | 채점 전용. 추출 경로에서 읽지 않는다 |
| `render_ses.py` | 읽기 전용 렌더러 |
| `S1-추출절차.md` | 과거 조건의 프롬프트 원문 출처. 추가만 하고 기존 절을 수정하지 않는다 |

### 9.2 작업 순서

```
 1. sesx/__init__.py                     패키지 뼈대
 2. sesx/rules/R2.json                   선별 규칙 (제외 사유 포함)            [P0]
 3. sesx/snapshot.py                     선별·해시·누출 검사                    [P0]
 4. tests/test_snapshot.py                                                      [P0]
 5. sesx/schema/*.schema.json            §4 계약                                [P1]
 6. sesx/contract.py                     로더·스키마 검증                       [P1]
 7. tests/test_contract.py                                                      [P1]
 8. sesx/index.py                        정규식 기호 색인                       [P2]
 9. sesx/evidence.py                     근거 위치 검증                         [P2]
10. tests/test_evidence.py                                                      [P2]
11. sesx/structure.py                    무결성·루트·순환·고립·도달             [P3]
12. tests/test_structure.py                                                     [P3]
13. sesx/assemble.py                     결정적 조립 + unresolved 이관          [P4]
14. tests/test_assemble.py                                                      [P4]
15. sesx/llm.py                          호출·재시도·원문 저장·usage 기록       [P5]
16. sesx/stages.py                       단계 정의·재개                         [P5]
17. sesx/prompts/T2/{a,b,c,e}.md         단계 프롬프트 (해시 기록 대상)         [P5]
18. extract.py                           CLI (snapshot/run/validate/report)     [P5]
19. sesx/report.py                       report.md                              [P5]
20. tests/test_stages_resume.py          가짜 LLM으로 재개 검증                 [P5]
── 여기까지가 최소 프로토타입(§8) ──
21. sesx/prompts/T2/{d,f}.md             결합·활성 조건 단계                    [P7]
22. sesx/semantics.py                    SEM-01~04 경고                         [P7]
23. sesx/repair.py + prompts/S2/         단일 추출 팔·수리 루프                 [P6]
24. score_ses.py 확장                    결합·활성 조건 지표 (기존 selftest 유지) [P7]
25. tests/test_score_couplings.py                                               [P7]
26. sesx/link_params.py                  매개변수 연결 **후보만** 생성          [이후]
```

전부 `docs/research/s1-ses-extraction/` 아래에 둔다. 기존 키트와 같은 디렉터리이므로
`score_ses.py`·`reference-ses.json` 재사용에 경로 문제가 없다.

### 9.3 매개변수 연결의 경계 (문제 11)

이 단계에서는 **후보 목록까지만** 만든다.

- 입력: `ses.json`의 속성 + `evidence`의 기호·행 위치
- 출력: `param_candidates.json` — `{attribute_id, symbol, file, line, literal_if_any,
  match_kind: exact_symbol|name_similarity|unresolved, confidence}`
- **하지 않는 것**: 단위·기본값·범위·의미의 생성, 결정기록(ledger)과의 자동 결합,
  실행 설정에의 반영
- 코드에 리터럴이 있으면 그 리터럴을 인용과 함께 후보에 싣는다. 없으면 `unresolved`.

---

## 10. 결정적 실험

### 10.1 경쟁 가설

| ID | 가설 |
|---|---|
| H1 | 추출을 단계로 나누면 근거 검증 통과율과 반복 안정성이 오른다 (후보 A 우위) |
| H2 | 단일 추출 + 검증 수리 루프가 같은 품질을 더 싸게 낸다 (후보 B 우위) |
| H3 | 품질을 정하는 것은 분해 방식이 아니라 **입력 선별과 근거 요구 형식**이다 (둘 다 부차적) |
| H4 | 코드가 만든 색인이 있으면 LLM의 역할은 분류로 줄어도 리콜이 유지된다 (후보 C 우위) |

### 10.2 통제 조건

전 팔 공통으로 고정한다.

- 같은 `snapshot_id` (같은 파일·같은 해시)
- 같은 모델·같은 `max_tokens`·같은 `response_format`
- 같은 데이터 계약·같은 검증기 버전(`sesx_sha256` 기록)
- 같은 채점 참조(ref-v7)·같은 별칭 사전 상태
- 반복 5회, 실행 순서 무작위
- 검증 결과는 추출 프롬프트에 되먹이지 않는다(수리 루프를 쓰는 S2 제외 — 그것이 그 팔의 정의다)

### 10.3 비교할 변경요소

| 실험 | 바꾸는 것 | 겨냥하는 가설 |
|---|---|---|
| E1 | 팔: S2-nr vs T2 | H1 vs H2 |
| E2 | 팔: S2-nr vs S2 (수리 루프 유무) | H2 |
| E3 | 단계 수: T2-min(4단계) vs T2(6단계) | H1 |
| E4 | 선별 규칙: R1(현행, ses 포함) vs R2(제외) — **R1은 오염 확인용 1회만** | H3 + F6 검증 |
| E5 | 근거 요구: 인용문 필수 vs 파일:행만 | H3 |
| E6 | 색인 리콜 상한 측정 (LLM 없음) | H4 |

E6은 가장 싸고 가장 먼저 한다. 색인이 만든 후보 집합에 참조 엔티티 51개 중 몇 개가
대응되는지를 사람이 표시해 상한을 낸다.

### 10.4 각 가설의 예상 결과

| 가설 | E1 | E2 | E3 | E5 | E6 |
|---|---|---|---|---|---|
| H1 참 | T2의 근거 통과율·Jaccard가 높음 | 수리가 격차를 못 메움 | 6단계 > 4단계 | 영향 작음 | — |
| H2 참 | 차이 작고 S2 토큰이 훨씬 적음 | 수리 후 T2와 대등 | 단계 수 무관 | 영향 작음 | — |
| H3 참 | 팔 차이 < 선별·근거 형식 차이 | 작음 | 작음 | **큼** | — |
| H4 참 | — | — | — | — | 상한 ≥ 85% |

### 10.5 현재 구현 권고를 바꾸게 할 결과

| 관측 | 권고 변경 |
|---|---|
| E6 상한 ≥ 85% | 후보 C를 주 경로로 승격. T2는 색인 분류 조립기로 축소 |
| E6 상한 < 60% | 후보 C 기각. 색인은 검증 보조로만 유지 |
| E1에서 S2-nr가 T2와 근거 통과율·Jaccard 모두 대등하고 토큰이 1/3 이하 | 기본 팔을 S2로 바꾸고 T2는 진단용으로 남김 |
| E3에서 6단계가 4단계보다 나쁨 | 단계 수를 줄인다. 분해가 이득이 아니라 흔들림의 원인 |
| E4에서 R1(오염)과 R2의 정확도 차가 큼 | 과거 12건의 해석을 재검토하고, 실험 전체를 R2 스냅샷으로 다시 놓는다 |
| E5에서 인용문 요구가 근거 통과율을 크게 올림 | 근거 형식을 전 팔에 강제하고, 팔 비교는 그 위에서 다시 한다 |
| `unresolved`가 50% 초과 | 게이트 재설계가 팔 비교보다 우선 |

---

## 부록 A — 문제 1~13에 대한 답의 위치

| # | 문제 | 답 |
|---|---|---|
| 1 | 포함·제외할 소스 파일 | §4.2 `selection_rule`, §1.5 F3·F4·F6 |
| 2 | 스냅샷·해시 고정 | §4.2, P0 |
| 3 | 단계 분리·저장·재개 | §2 후보 A, §4.7 `stage_status`, P5 |
| 4 | Entity vs 소프트웨어 클래스 | 부록 B.1 |
| 5 | Attribute/ASPECT/SPEC/MULTI/Coupling 판단 기준 | 부록 B.2~B.4 |
| 6 | 근거 저장 구조 | §4.4 |
| 7 | 근거 일치 검증 | §6.2, P2 |
| 8 | 무결성·루트·순환·고립·도달 | §6.3·§6.4, P3 |
| 9 | 의미 오류 검토 | §6.5·§6.7 |
| 10 | 단일 vs 단계별 비교 | §7, §10 |
| 11 | 매개변수 연결 범위 | §9.3 |
| 12 | 최소 기능 | §8.2 |
| 13 | 중단·재설계 조건 | §7.10, §10.5 |

## 부록 B — 판단 기준표 (프롬프트와 검증기가 공유한다)

### B.1 Entity인가

**Entity로 본다**
- 시간이 흐르면 상태가 바뀐다 — 근거가 할당·증감·컬렉션 변형 줄을 가리킨다
- 같은 유형이 여럿 존재하고 개수가 정해진다
- 시뮬레이션 결과의 의미를 바꾼다

**Entity로 보지 않는다**
- 값 하나 (→ Attribute)
- 계산 결과 (→ 관측의 Attribute)
- 실행 환경: 프레임워크·HTTP·로깅·영속화·도구 노출·모델 호출·기록 장치
- 직렬화 전용 DTO
- 인터페이스·오류 타입·품질 표시

**클래스가 아니어도 Entity일 수 있다** — 이벤트 종류, 상태 배열, 반복문 안의 판정.
이 경우 근거는 그 판정이 일어나는 행을 가리켜야 한다.

**모호하면** `unresolved`에 남긴다. 클래스 목록을 그대로 옮긴 결과는 STRUCT-05(고립)와
SEM-01(선택자 없음)에서 대량 경고로 드러난다.

### B.2 Attribute인가

- 그 값이 **무엇을 서술하는가**로 소유를 정한다. 선언 위치가 아니다
- 설정 객체에 모여 선언된 값도 각자 서술 대상 아래로 간다
- 소유 엔티티를 못 정하면 `unresolved` (`reason_code: unknown_reference`)
- 단위·기본값·범위는 **코드에 리터럴이 있을 때만** 후보로 싣는다. 없으면 `null`

### B.3 ASPECT / SPEC / MULTI

| 관계 | 코드에서의 표시 | 필수 근거 |
|---|---|---|
| ASPECT | 자식들이 같은 실행에서 함께 계산·갱신된다 | 두 자식 이상의 상태 변경이 같은 흐름에 있음 |
| SPEC | 하나를 골라 확정한다 — enum, 설정 필드에 의한 분기, 전략 선택 | `selector.symbol` 지목 |
| MULTI | 같은 유형을 개수만큼 복제한다 — 배열·컬렉션과 크기 결정 지점 | 크기를 정하는 자리 지목 |

축이 둘 이상이면 **합치지 않는다.** 합치면 축별 선택이 사라진다.

### B.4 Coupling

- **결합이다**: A의 상태를 바꾸는 코드가 B에서 온 값·이벤트를 쓴다
- **결합이 아니다**: 객체가 객체를 필드로 보유, 타입 사용, 단순 함수 인자 전달
- 포트 이름은 오가는 것을 가리킨다 (`수거지점.적재량` 처럼)
- 근거는 **상태 변경 지점**을 가리켜야 한다 (CPL-02)

### B.5 활성 조건

- 조건을 특정했으면 `asserted` + `expression` + `selector_symbol` + 근거
- 조건 분기가 없음을 색인으로 확인했으면 `unconditional_verified`
- 그 밖에는 전부 `unresolved`
- **모르는 것을 "항상 활성"으로 적는 경로는 구현하지 않는다**
