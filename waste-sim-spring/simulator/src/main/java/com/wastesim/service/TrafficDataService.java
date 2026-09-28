package com.wastesim.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.model.TrafficProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code src/main/resources/traffic/*.json}의 교통 프로파일을 로드해 id로 조회한다.
 * (TRAFFIC_EXTENSION_DESIGN.md §2.1) 클래스패스에 시드 데이터가 없으면 조회 시
 * {@code null}을 반환하고, 호출측(검증기·엔진)은 교통 가중치를 적용하지 않는다
 * (fail-closed이 아니라 "교통 레이어 비활성"으로 안전하게 폴백 — 필수값이 아님).
 *
 * <p><b>두 프로파일은 서로 다른 것을 잰다.</b>
 *
 * <ul>
 *   <li>{@code jangryang-weekday}(기본) — SK TMAP 실측 <b>소요시간</b>의 지체 배수.
 *       교통 구역 4곳 사이 12개 순서쌍 x 24시간을 조회해, 각 구간의 하루 최소 소요시간
 *       대비 배수를 도착 구역별로 평균했다. 전역 피크 <b>18시(1.73)</b>, 최혼잡 구역
 *       {@code Node_C}·{@code Node_A}(2.14·2.12), 최한산 {@code Node_D}(1.40).</li>
 *   <li>{@code jangryang-volume-weekday} — 공공데이터포털 포항시 <b>교통량</b>에서
 *       유도한 값({@code scripts/preprocess_response_filtered.py}). 전역 피크 13시(1.78),
 *       최혼잡 {@code Node_A}(2.20), 최한산 {@code Node_B}(1.46).</li>
 * </ul>
 *
 * <p>2026-09-02 TMAP 24시간 실측과 대조한 결과 <b>통행량 기반 프로파일의 피크 시각이 실제와
 * 반대였다</b>(13시 vs 18시). 통행량은 낮에 고르게 많지만 지체는 용량을 넘는 순간 급증하므로
 * 퇴근 시간에 몰린다 — 용량 항이 없는 공식이 그 차이를 담지 못했다. 진폭(1.71배)은 양쪽이
 * 같았고 시간 분포만 달랐다. 근거는 {@code docs/guides/CONNECT_TRAFFIC_CSV.md} §3.5.
 *
 * <p><b>Python 참조 엔진은 이 클래스를 거치지 않는다.</b> 자기 사본의 CSV에서 통행량
 * 프로파일을 직접 만들고 {@code trafficProfileId}를 읽지 않으므로, 두 엔진을 대조할 때는
 * Java 쪽에 {@code jangryang-volume-weekday}를 명시해야 한다.
 */
@Service
public class TrafficDataService {

    private static final Logger log = LoggerFactory.getLogger(TrafficDataService.class);
    /**
     * 기동 시 읽는 프로파일. 두 개인 이유는 서로 <b>다른 것을 재기 때문</b>이다 —
     * {@code jangryang-weekday}는 TMAP 실측 소요시간의 지체 배수이고,
     * {@code jangryang-volume-weekday}는 공공데이터 통행량에서 유도한 값이다. 후자는
     * 2026-09-02 실측 대조에서 피크 시각이 실제와 달랐지만(13시 vs 18시), 지금까지의 결과가
     * 그 값으로 나왔으므로 비교용으로 남긴다.
     */
    private static final String[] SEED_IDS = {"jangryang-weekday", "jangryang-volume-weekday"};

    /**
     * 프로파일 id 없이 "교통 반영해줘"라고만 했을 때 쓸 기본 프로파일 —
     * <b>TMAP 실측 소요시간</b>이다.
     *
     * <p>이름으로 못박아 둔 이유가 있다. 예전에는 {@code profiles.keySet().findFirst()}로
     * 골랐는데, 그 맵은 {@link ConcurrentHashMap}이라 순회 순서가 <b>등록 순서도 사전
     * 순서도 아닌 해시 순서</b>다. 프로파일이 하나였을 때는 답이 하나뿐이라 드러나지 않았고,
     * 2026-09-02에 통행량 기반 구 프로파일이 비교용으로 함께 등록되면서 기본값이
     * <b>어느 쪽이 될지 코드로 결정되지 않는 상태</b>가 됐다.
     *
     * <p>그 둘은 서로 다른 답을 낸다 — 구 프로파일의 피크는 13시, 실측은 18시이고, 그
     * 차이 때문에 "가장 나쁜 수거 시각"이 뒤집힌다. 즉 순회 순서가 실험 결론을 바꿀 수
     * 있었다. 기본값은 실측 쪽이어야 하므로 이름으로 고정한다.
     */
    public static final String DEFAULT_PROFILE_ID = "jangryang-weekday";

    /**
     * 기동 시 읽는 프로파일 id들. 전처리 스크립트의 기본 출력 대상이 이 목록 안에 있어야
     * 한다 — 어긋나면 갱신 절차를 따라도 반영되지 않고, 그 사실이 아무 데도 드러나지 않는다
     * (실제로 그런 상태였다. {@code ScriptOutputTargetTest}가 고정한다).
     */
    public static java.util.List<String> seedIds() {
        return java.util.List.of(SEED_IDS);
    }

    private final Map<String, TrafficProfile> profiles = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public TrafficDataService() {
        for (String id : SEED_IDS) {
            String path = "/traffic/" + id + ".json";
            try (InputStream in = getClass().getResourceAsStream(path)) {
                if (in == null) {
                    log.warn("교통 프로파일 리소스 없음: {}", path);
                    continue;
                }
                TrafficProfile p = mapper.readValue(in, TrafficProfile.class);
                profiles.put(p.getId(), p);
            } catch (Exception e) {
                log.error("교통 프로파일 로드 실패: {}", path, e);
            }
        }
        if (!profiles.containsKey(DEFAULT_PROFILE_ID)) {
            // 기동 자체를 막지는 않는다 — 교통 레이어는 선택 기능이고, 끈 실험은 그대로
            // 돌아야 한다. 다만 이 상태에서 "교통 반영해줘"는 기본값이 없어 검증에서
            // 막히므로, 왜 막히는지를 로그에 남긴다.
            log.error("기본 교통 프로파일 {}이 등록되지 않았다 — 프로파일을 지정하지 않은 "
                    + "교통 반영 요청은 검증에서 거부된다. 등록된 프로파일: {}",
                    DEFAULT_PROFILE_ID, profiles.keySet());
        }
    }

    /** id로 조회. 없으면 null(호출측에서 교통 레이어 미적용으로 처리). */
    public TrafficProfile find(String id) {
        if (id == null) return null;
        return profiles.get(id);
    }

    /**
     * 사용자가 프로파일 id 없이 "교통 반영해줘"라고만 말했을 때 쓸 기본값 —
     * {@link #DEFAULT_PROFILE_ID}가 등록돼 있으면 그것이고, 없으면 {@code null}이다.
     *
     * <p><b>남은 프로파일 중 아무거나 대신 주지 않는다.</b> 기본값이 실측이 아닌 다른
     * 프로파일로 조용히 바뀌면 결론이 달라지는데(피크 13시 vs 18시) 화면에는 아무 표시도
     * 남지 않는다. {@code null}을 주면 검증기가 "교통 레이어를 사용하려면
     * trafficProfileId가 필요합니다"로 막으므로, 실패가 결과처럼 보이지 않는다.
     */
    public String defaultProfileId() {
        return profiles.containsKey(DEFAULT_PROFILE_ID) ? DEFAULT_PROFILE_ID : null;
    }

    /** 프로파일을 직접 등록(테스트 전용 시나리오 구성, 또는 향후 동적 데이터 갱신용). */
    public void register(TrafficProfile p) {
        if (p != null && p.getId() != null) profiles.put(p.getId(), p);
    }
}
