# S1 추출 키트 — 시뮬레이터 소스 코드 → SES

```
S1-추출절차.md       절차서. 입력 선별 규칙 · 프롬프트 전문 · 출력 스키마 · 채점 방법
reference-ses.json   참조 SES(정답지). ref-v8 — 시스템 구성 골격. 엔티티 62 · 근거 98
reference-ses-v7.json  ref-v7 동결본. 과거 실행 12건은 이것으로 잰다
참조-검증-기록.md     그 대조 기록. 무엇을 고쳤고 무엇이 아직 미검증인가
score_ses.py         채점 스크립트. 자체 검증 6건 포함
aliases.json         이름 별칭 사전(채점자용). 추출기에는 주지 않는다
runs/                실행 결과를 여기에 모은다. DEMO-*.json은 보고서 모양을 보이려고
                     참조를 일부러 망가뜨린 합성 파일이며 실제 LLM 출력이 아니다
```

## 쓰는 법

```bash
python3 score_ses.py --selftest                                          # 스크립트 자체 검증
python3 check_ref_v8.py all                                              # 정답지 구조 불변식
python3 score_ses.py --ref reference-ses-v7.json --runs 'runs/gpt-4.1-mini-C*.json'   # 과거 12건
python3 score_ses.py --runs 'exp/새실행/ses.json'                          # 새 실행 (기본이 v8)
```

## 돌리는 순서

1. ~~`S1-추출절차.md`의 7절 — 참조 SES를 코드와 대조해 확정한다~~ — **부분 완료**(2026-09-11).
   임계점·엔티티·속성을 대조해 5건을 고쳤다(`참조-검증-기록.md`). `couplings` 포트 이름과
   CP-4 소속은 아직 미검증이다
2. 같은 절차서의 1절 규칙으로 입력 파일을 모은다 (C 조건)
3. 3절 프롬프트를 그대로 써서 5회 실행, 원본을 `runs/`에 저장
4. `score_ses.py`로 채점
5. 미매칭 이름을 `aliases.json`에 등록하고 다시 채점

## 정답지가 둘이다 — v7과 v8

```
reference-ses.json       ref-v8. 시스템 구성 골격(설정·모델·실험·결과). 새 실행은 이것으로 잰다
reference-ses-v7.json    ref-v7 동결본. 과거 실행 12건(C·Cs·Csr·Cd)은 이것으로 잰다
```

v8은 엔티티가 51 → 62개다. 늘어난 11개(설정 6 · 시스템 5)는 추출기가 낼 수 없는 구조이므로,
과거 실행을 v8로 채점하면 재현율이 떨어진다. **그 하락은 추출 품질의 변화가 아니라 정답지
변경의 결과다. 두 수를 같은 표에 놓지 않는다.**

```bash
python score_ses.py --ref reference-ses-v7.json --runs 'runs/gpt-4.1-mini-C*.json'   # 과거 12건
python score_ses.py --runs 'exp/새실행/ses.json'                                     # 새 실행 (기본값이 v8)
```

## 주의 — DEMO 파일은 ref-v2 기준이다

`runs/DEMO-*.json`은 교정 전 정답지로 합성한 파일이다. ref-v7 동결본으로 채점하면 `교통혼잡판정`·
`기타직업`이 과잉으로, `야간교대근무자`·`1인직장인`이 누락으로 잡힌다. **의도한 동작이다** —
교정된 정답지가 낡은 구조를 잡아내는 것이다. 실제 LLM 출력이 아니므로 다시 만들지 않았다.

## 신규 경로 — `sesx/` · `extract.py`

교차검토 결론에 따라 만든 추출·보존·검증·검토 기반이다. 위 도구들과 **별개로** 돈다.
`run_s1.py` · `run_t.py` · `runs/` 는 과거 조건을 그대로 두기 위해 건드리지 않는다.

```bash
python extract.py pipeline --rule P-pilot --run-id r1 --plan T2 --model gpt-4.1-mini
python -m unittest discover -s tests
```

설계와 규칙은 `sesx/README.md`, 계획은 `docs/superpowers/plans/2026-09-14-ses-extraction-verification-plan.md` 에 있다.
