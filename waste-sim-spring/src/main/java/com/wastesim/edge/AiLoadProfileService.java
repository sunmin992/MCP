package com.wastesim.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code src/main/resources/edge/ai-load-*.json}의 AI 부하 패턴을 로드해 id로 조회한다
 * ({@link com.wastesim.service.TrafficDataService}와 같은 시드 로딩 패턴).
 *
 * <p>교통 프로파일과 마찬가지로 <b>필수값이 아니다</b> — id를 못 찾으면 null을 돌려주고
 * 호출측은 기존 상수 부하로 동작한다(fail-closed가 아니라 "패턴 레이어 비활성"으로
 * 폴백). 부하 패턴은 기존 실험을 확장하는 것이지 대체하는 것이 아니기 때문이다.
 *
 * <p>등록 순서가 곧 실험 순서다 — {@code steady}(대조군) → {@code burst} →
 * {@code mixed}. 대조군을 반드시 함께 돌려야 "패턴 때문에 순위가 바뀌었다"를
 * 주장할 수 있다.
 */
@Service
public class AiLoadProfileService {

    private static final Logger log = LoggerFactory.getLogger(AiLoadProfileService.class);
    private static final String[] SEED_IDS = {"steady", "burst", "mixed"};

    /** 등록 순서를 유지한다(실험 순서와 같고, 목록 출력이 매번 같은 순서여야 한다). */
    private final Map<String, AiLoadProfile> profiles = new LinkedHashMap<>();

    public AiLoadProfileService() {
        ObjectMapper mapper = new ObjectMapper();
        for (String id : SEED_IDS) {
            String path = "/edge/ai-load-" + id + ".json";
            try (InputStream in = getClass().getResourceAsStream(path)) {
                if (in == null) {
                    log.warn("AI 부하 패턴 리소스 없음: {}", path);
                    continue;
                }
                AiLoadProfile p = mapper.readValue(in, AiLoadProfile.class);
                if (p.getSegments().isEmpty()) {
                    log.warn("AI 부하 패턴에 구간이 없어 건너뛴다: {}", path);
                    continue;
                }
                profiles.put(p.getId(), p);
            } catch (Exception e) {
                log.error("AI 부하 패턴 로드 실패: {}", path, e);
            }
        }
    }

    /** id로 조회. 없으면 null(호출측에서 상수 부하로 처리). */
    public AiLoadProfile find(String id) {
        if (id == null) return null;
        return profiles.get(id.trim().toLowerCase());
    }

    /** 등록된 패턴 전체(등록 순서). */
    public List<AiLoadProfile> all() {
        return List.copyOf(profiles.values());
    }

    /** 패턴을 지정하지 않았을 때의 기본값 — 기존 동작과 같은 대조군. */
    public AiLoadProfile defaultProfile() {
        return profiles.get("steady");
    }
}
