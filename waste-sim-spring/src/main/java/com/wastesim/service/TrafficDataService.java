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
 * <p>{@code jangryang-weekday}는 공공데이터포털(data.go.kr) 포항시 교통 통계
 * 원자료(장량동 인근 8개 지점 키워드로 필터링)를 전처리해 생성한 실측 프로파일이다
 * ({@code scripts/preprocess_response_filtered.py} 참고) — 가정값이 아닌 실데이터가
 * 유일한 진실 원천이다(대응 A). {@code Node_A}(장성초등학교 인근)가 점심(12~13시)
 * 최혼잡 구간, {@code Node_B}(양덕사거리)는 상대적으로 한산하다.
 */
@Service
public class TrafficDataService {

    private static final Logger log = LoggerFactory.getLogger(TrafficDataService.class);
    private static final String[] SEED_IDS = {"jangryang-weekday"};

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
    }

    /** id로 조회. 없으면 null(호출측에서 교통 레이어 미적용으로 처리). */
    public TrafficProfile find(String id) {
        if (id == null) return null;
        return profiles.get(id);
    }

    /**
     * 사용자가 프로파일 id 없이 "교통 반영해줘"라고만 말했을 때 쓸 기본값.
     * 등록된 프로파일이 1개뿐인 현재는 그걸 그대로 반환한다(여러 개로 늘어나면
     * 재검토 필요).
     */
    public String defaultProfileId() {
        return profiles.keySet().stream().findFirst().orElse(null);
    }

    /** 프로파일을 직접 등록(테스트 전용 시나리오 구성, 또는 향후 동적 데이터 갱신용). */
    public void register(TrafficProfile p) {
        if (p != null && p.getId() != null) profiles.put(p.getId(), p);
    }
}
