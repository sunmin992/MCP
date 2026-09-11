package com.wastesim.ses;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 장량동 시뮬레이터의 SES 트리. 참조 ref-v7에서 옮겨 적었고,
 * JangnyangStructureMatchesReferenceTest가 그 정답지와 갈라졌는지 지킨다.
 *
 * <p>이 선언이 <b>문항 세트의 출처</b>다. 여기 spec 축이 하나 늘면 물어볼 것이 하나 는다.
 */
public final class JangnyangEntityStructure {

    private static final EntityStructure INSTANCE = build();

    private JangnyangEntityStructure() { }

    public static EntityStructure get() {
        return INSTANCE;
    }

    private static EntityStructure build() {
        Map<String, SesEntity> e = new LinkedHashMap<>();
        e.put("장량동 생활쓰레기 수거 시뮬레이터", new SesEntity("장량동 생활쓰레기 수거 시뮬레이터", List.of("대상지역", "기준연도"), List.of(new Decomposition(Decomposition.Kind.ASPECT, "최상위", List.of("대상 시스템", "실험", "관측")))));
        e.put("대상 시스템", new SesEntity("대상 시스템", List.of("대상지역", "시간범위", "1인배출량", "배출량변동"), List.of(new Decomposition(Decomposition.Kind.ASPECT, "구성", List.of("거주민 집합", "수거지점 집합", "폐기물 유형 집합", "수거차량 집합", "교통구역 집합", "수거 경로", "민원 판정")))));
        e.put("거주민 집합", new SesEntity("거주민 집합", List.of("개수", "직업구성"), List.of(new Decomposition(Decomposition.Kind.MULTI, "거주민 다중", List.of("거주민")))));
        e.put("거주민", new SesEntity("거주민", List.of("직업", "외출시각", "귀가시각", "외출시각변동"), List.of(new Decomposition(Decomposition.Kind.SPEC, "직업 축", List.of("생산직", "학생", "전업주부", "야간 교대근무자", "1인 직장인")), new Decomposition(Decomposition.Kind.SPEC, "배출시각 모델 축", List.of("직업별 외출시각 기반", "포항시 배출시간대 기반")))));
        e.put("생산직", new SesEntity("생산직", List.of(), List.of()));
        e.put("학생", new SesEntity("학생", List.of(), List.of()));
        e.put("전업주부", new SesEntity("전업주부", List.of(), List.of()));
        e.put("직업별 외출시각 기반", new SesEntity("직업별 외출시각 기반", List.of("외출시각분포"), List.of()));
        e.put("포항시 배출시간대 기반", new SesEntity("포항시 배출시간대 기반", List.of("배출허용창"), List.of()));
        e.put("수거지점 집합", new SesEntity("수거지점 집합", List.of("개수"), List.of(new Decomposition(Decomposition.Kind.MULTI, "수거지점 다중", List.of("수거지점")))));
        e.put("수거지점", new SesEntity("수거지점", List.of("적재량", "교통구역", "차량진입가능"), List.of()));
        e.put("폐기물 유형 집합", new SesEntity("폐기물 유형 집합", List.of(), List.of(new Decomposition(Decomposition.Kind.MULTI, "폐기물 유형 다중", List.of("폐기물 유형")))));
        e.put("폐기물 유형", new SesEntity("폐기물 유형", List.of("종류", "배출비율", "용량", "임계값", "수거주기", "수거요일"), List.of()));
        e.put("수거차량 집합", new SesEntity("수거차량 집합", List.of("개수"), List.of(new Decomposition(Decomposition.Kind.MULTI, "수거차량 다중", List.of("수거차량")))));
        e.put("수거차량", new SesEntity("수거차량", List.of("적재용량", "지점당수거시간", "수거시각", "수거요일", "초기적재량", "배차간격"), List.of(new Decomposition(Decomposition.Kind.SPEC, "차종 축", List.of("5톤 차량", "2.5톤 차량", "1톤 차량")))));
        e.put("5톤 차량", new SesEntity("5톤 차량", List.of(), List.of()));
        e.put("2.5톤 차량", new SesEntity("2.5톤 차량", List.of(), List.of()));
        e.put("1톤 차량", new SesEntity("1톤 차량", List.of(), List.of()));
        e.put("교통구역 집합", new SesEntity("교통구역 집합", List.of(), List.of(new Decomposition(Decomposition.Kind.MULTI, "교통구역 다중", List.of("교통 구역")))));
        e.put("교통 구역", new SesEntity("교통 구역", List.of("혼잡계수", "시간대프로파일", "적색임계값"), List.of()));
        e.put("수거 경로", new SesEntity("수거 경로", List.of("방문순서", "총이동거리"), List.of(new Decomposition(Decomposition.Kind.SPEC, "이동시간 방식 축", List.of("구간 상수", "교통구역 근사", "실제 도로 기반")))));
        e.put("구간 상수", new SesEntity("구간 상수", List.of("구간이동시간"), List.of()));
        e.put("교통구역 근사", new SesEntity("교통구역 근사", List.of("구역내이동시간", "구역배정가정"), List.of()));
        e.put("실제 도로 기반", new SesEntity("실제 도로 기반", List.of(), List.of()));
        e.put("민원 판정", new SesEntity("민원 판정", List.of(), List.of(new Decomposition(Decomposition.Kind.ASPECT, "판정 구성", List.of("적재초과 판정", "임대인 점검 판정", "교통혼잡 판정")))));
        e.put("적재초과 판정", new SesEntity("적재초과 판정", List.of("임계값초과판정"), List.of()));
        e.put("임대인 점검 판정", new SesEntity("임대인 점검 판정", List.of("점검시각", "잔류량"), List.of()));
        e.put("실험", new SesEntity("실험", List.of("기간", "반복횟수", "난수시드"), List.of(new Decomposition(Decomposition.Kind.SPEC, "실험 유형 축", List.of("단일 실행", "직업 구성 실험", "수거시각 실험", "배출행동 실험", "용량·임계값 실험", "밀도 실험", "수거일정 실험", "다중차량 실험", "분리배출 실험", "확장직업 실험", "결합변형 실험", "월별배출 실험", "차종·방문순서 실험")))));
        e.put("수거시각 실험", new SesEntity("수거시각 실험", List.of(), List.of()));
        e.put("배출행동 실험", new SesEntity("배출행동 실험", List.of(), List.of()));
        e.put("차종·방문순서 실험", new SesEntity("차종·방문순서 실험", List.of(), List.of()));
        e.put("관측", new SesEntity("관측", List.of("교통패널티", "총배출량", "미수거수요", "질량수지오차", "월별배출량", "시드"), List.of(new Decomposition(Decomposition.Kind.ASPECT, "관측 구성", List.of("민원 통계", "적재량 통계", "차량 통계", "시간 통계", "시나리오 비교", "데이터 품질·가정")))));
        e.put("민원 통계", new SesEntity("민원 통계", List.of("총민원", "직업별", "일별", "적재초과민원", "임대인민원", "평균민원", "민원표준편차", "시드별총민원"), List.of()));
        e.put("적재량 통계", new SesEntity("적재량 통계", List.of("최대적재", "잔류적재", "건물별잔류", "유형별잔류", "트럭별잔류", "최대잔류건물"), List.of()));
        e.put("차량 통계", new SesEntity("차량 통계", List.of("수거량", "이용률", "배정용량이용률", "운행별계측", "부분수거횟수", "미수거횟수", "용량소진운행수"), List.of()));
        e.put("시간 통계", new SesEntity("시간 통계", List.of("수거완료시각"), List.of()));
        e.put("시나리오 비교", new SesEntity("시나리오 비교", List.of("최적", "최악", "개선폭"), List.of()));
        e.put("데이터 품질·가정", new SesEntity("데이터 품질·가정", List.of("좌표정확도", "가정목록", "품질표시"), List.of()));
        e.put("야간 교대근무자", new SesEntity("야간 교대근무자", List.of(), List.of()));
        e.put("1인 직장인", new SesEntity("1인 직장인", List.of(), List.of()));
        e.put("교통혼잡 판정", new SesEntity("교통혼잡 판정", List.of("민원가중치"), List.of()));
        e.put("단일 실행", new SesEntity("단일 실행", List.of(), List.of()));
        e.put("직업 구성 실험", new SesEntity("직업 구성 실험", List.of(), List.of()));
        e.put("용량·임계값 실험", new SesEntity("용량·임계값 실험", List.of(), List.of()));
        e.put("밀도 실험", new SesEntity("밀도 실험", List.of(), List.of()));
        e.put("수거일정 실험", new SesEntity("수거일정 실험", List.of(), List.of()));
        e.put("다중차량 실험", new SesEntity("다중차량 실험", List.of(), List.of()));
        e.put("분리배출 실험", new SesEntity("분리배출 실험", List.of(), List.of()));
        e.put("확장직업 실험", new SesEntity("확장직업 실험", List.of(), List.of()));
        e.put("결합변형 실험", new SesEntity("결합변형 실험", List.of(), List.of()));
        e.put("월별배출 실험", new SesEntity("월별배출 실험", List.of(), List.of()));

        List<Coupling> couplings = List.of(
                new Coupling("거주민.배출", "수거지점.적재", "DischargeEvt(227) -> fill[building][type] += amount * WasteType.fraction (SimulationEngine:457). 외출·귀가 두 시점으로 분할 배출된다(:363)", null),
                new Coupling("수거지점.적재량", "적재초과 판정.판정", "별도 메시지가 아니라 DischargeEvt 처리 안에서 즉시 판정한다 — fill/WasteType.capacity >= WasteType.threshold이면 1건(:459-462). 한 종류라도 넘으면 건물당 1건이다", null),
                new Coupling("수거지점.적재량", "임대인 점검 판정.판정", "InspectEvt(236)가 별도 시각에 도착해 worst = max(fill/capacity)를 전역 cfg.landlordThreshold와 비교한다(:466-475). 적재초과와 임계값 소유자도 판정 시점도 다르므로 결합을 나눈다", null),
                new Coupling("수거차량.수거", "수거지점.비움", "CollectEvt(213) -> 그날 due인 종류만 fill[b][t] *= keepFraction(:420). 트럭 잔여용량이 부족하면 적재량 비례로 부분 수거한다(:416-425)", null),
                new Coupling("교통 구역.혼잡계수", "수거 경로.이동시간", "TrafficProfile.weightAt(도착분, 구역) -> congestionWeight -> hopMinutes(..., congestionWeight)로 다음 지점 도착 시각을 늦춘다(:336,:343). 공식은 TravelTimeCalculator에 고정돼 RouteDurationEstimator와 공유된다", "trafficMode=APPLY (문항 24) — NONE이면 trafficProfile이 null이라 이 결합이 통째로 죽는다(SimulationEngine:271 · 330-339)"),
                new Coupling("교통 구역.혼잡계수", "교통혼잡 판정.판정", "TrafficProfile.isRed(도착분, 구역)이면 cfg.trafficComplaintWeight를 누적한다(:337-339). 참조 초안에 없던 결합이다 — 교통 구역은 이동시간과 혼잡 판정 두 곳으로 간다", "trafficMode=APPLY (문항 24) — NONE이면 trafficProfile이 null이라 이 결합이 통째로 죽는다(SimulationEngine:271 · 330-339)")
        );

        return new EntityStructure("장량동 생활쓰레기 수거 시뮬레이터", e, couplings);
    }
}
