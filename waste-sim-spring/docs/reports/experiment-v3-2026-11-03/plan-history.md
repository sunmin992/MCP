# 판정 기록 집계

실행 64개 가운데 판정 기록이 있는 실행 23개(planId 를 받는 서버는 장량동뿐이다).

| 지표 | 값 |
|---|---|
| 완료에 닿은 실행 | 22 / 23 |
| 평균 단계(round) | 2.22 |
| 평균 재확인(recheck) | 0.22 |
| 평균 완료 단계 | 2.27 |
| 평균 최소 필요 단계 | 1.55 |
| 최소 단계에 끝난 실행 | 6 / 22 |
| 브로커 matchId 가 이어진 실행 | 23 / 23 |

마지막 판정의 FILLED 근거 표시: NOT_CHECKED 211, UNVERIFIED 15, VERIFIED 100

단계 중 붙은 UNFILLED 사유: NO_EVIDENCE 11, OUT_OF_RANGE 3

## 실행별

| 실행 | 매칭 판정 | 단계 | 재확인 | 완료 | 최소 | 초과 | 사유 | 생성 |
|---|---|---|---|---|---|---|---|---|
| V3-D1-A-r1 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-D1-A-r2 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-D1-A-r3 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-D2-A-r1 | ADJUST_REQUEST → MATCH | 0 | 0 | - | - | - | - | - |
| V3-D2-A-r2 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-D2-A-r3 | ADJUST_REQUEST → MATCH | 0 | 0 | - | - | - | - | - |
| V3-D3-A-r1 | ADJUST_REQUEST → MATCH | 0 | 0 | - | - | - | - | - |
| V3-D3-A-r2 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-D3-A-r3 | ADJUST_REQUEST → MATCH | 0 | 0 | - | - | - | - | - |
| V3-D4-A-r1 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-D4-A-r2 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-D4-A-r3 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-D5-A-r1 | ADJUST_REQUEST → MATCH | 0 | 0 | - | - | - | - | - |
| V3-D5-A-r2 | ADJUST_REQUEST → MATCH | 0 | 0 | - | - | - | - | - |
| V3-D5-A-r3 | ADJUST_REQUEST → MATCH | 0 | 0 | - | - | - | - | - |
| V3-D6-A-r1 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-D6-A-r2 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-D6-A-r3 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-J1-A-r1 | MATCH | 2 | 1 | 2 | 2 | 0 | NO_EVIDENCE:1 | ok |
| V3-J1-A-r2 | MATCH | 2 | 1 | 2 | 2 | 0 | NO_EVIDENCE:1 | ok |
| V3-J1-A-r3 | MATCH | 2 | 1 | 2 | 2 | 0 | NO_EVIDENCE:1 | ok |
| V3-J2-A-r1 | CHOOSE | 2 | 0 | 2 | 1 | 1 | - | ok |
| V3-J2-A-r2 | CHOOSE | 2 | 0 | 2 | 1 | 1 | - | ok |
| V3-J2-A-r3 | CHOOSE | 2 | 0 | 2 | 1 | 1 | - | ok |
| V3-J3-A-r1 | ADJUST_REQUEST → MATCH | 4 | 0 | 4 | 3 | 1 | NO_EVIDENCE:1 | 거절(None), 거절(None), 거절(ERROR), ok, ok |
| V3-J3-A-r2 | ADJUST_REQUEST → MATCH | 3 | 0 | 3 | 3 | 0 | NO_EVIDENCE:1 | 거절(None), 거절(None), 거절(ERROR) |
| V3-J3-A-r3 | ADJUST_REQUEST → MATCH | 3 | 0 | 3 | 3 | 0 | NO_EVIDENCE:1 | 거절(None), 거절(None), 거절(ERROR) |
| V3-J4-A-r1 | ADJUST_REQUEST → MATCH | 2 | 0 | 2 | 1 | 1 | - | ok |
| V3-J4-A-r2 | ADJUST_REQUEST → MATCH | 2 | 0 | 2 | 1 | 1 | - | ok |
| V3-J4-A-r3 | ADJUST_REQUEST → MATCH | 2 | 0 | 2 | 1 | 1 | - | ok |
| V3-J5-A-r1 | ADJUST_REQUEST → MATCH | 2 | 0 | 2 | 1 | 1 | OUT_OF_RANGE:1 | ok |
| V3-J5-A-r2 | ADJUST_REQUEST → MATCH | 2 | 0 | 2 | 1 | 1 | NO_EVIDENCE:1, OUT_OF_RANGE:1 | ok |
| V3-J5-A-r3 | ADJUST_REQUEST → MATCH | 2 | 0 | 2 | 1 | 1 | OUT_OF_RANGE:1 | ok |
| V3-J6-A-r1 | MATCH | 2 | 0 | 2 | 1 | 1 | - | ok |
| V3-J6-A-r2 | ADJUST_REQUEST → MATCH | 2 | 0 | 2 | 1 | 1 | - | ok |
| V3-J6-A-r3 | ADJUST_REQUEST → MATCH | 2 | 0 | 2 | 1 | 1 | NO_EVIDENCE:1 | ok |
| V3-P1-A-r1 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-P1-A-r2 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-P1-A-r3 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-P2-A-r1 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-P2-A-r2 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-P2-A-r3 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-P3-A-r1 | ADJUST_REQUEST → MATCH | 0 | 0 | - | - | - | - | - |
| V3-P3-A-r2 | ADJUST_REQUEST → MATCH | 0 | 0 | - | - | - | - | - |
| V3-P3-A-r3 | ADJUST_REQUEST → MATCH | 0 | 0 | - | - | - | - | - |
| V3-P4-A-r1 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-P4-A-r2 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-P4-A-r3 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-P5-A-r1 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-P5-A-r2 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-P5-A-r3 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-P6-A-r1 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-P6-A-r2 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-P6-A-r3 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-S1-A-r1 | MATCH | 0 | 0 | - | - | - | - | - |
| V3-S10-A-r1 | NONE | 0 | 0 | - | - | - | - | - |
| V3-S2-A-r1 | CHOOSE | 0 | 0 | - | - | - | - | - |
| V3-S3-A-r1 | CHOOSE | 3 | 0 | 3 | 2 | 1 | NO_EVIDENCE:1 | ok |
| V3-S4-A-r1 | ADJUST_REQUEST → MATCH | 2 | 2 | 2 | 2 | 0 | NO_EVIDENCE:1 | ok |
| V3-S5-A-r1 | ADJUST_REQUEST → MATCH | 3 | 0 | 3 | 2 | 1 | NO_EVIDENCE:1 | ok |
| V3-S6-A-r1 | ADJUST_REQUEST → MATCH | 2 | 0 | 2 | 1 | 1 | - | ok |
| V3-S7-A-r1 | ADJUST_REQUEST | 0 | 0 | - | - | - | - | - |
| V3-S8-A-r1 | MATCH | 1 | 0 | - | 1 | - | - | - |
| V3-S9-A-r1 | ADJUST_REQUEST → ADJUST_REQUEST → ADJUST_REQUEST | 0 | 0 | - | - | - | - | - |
