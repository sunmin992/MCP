package com.wastesim.edge;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 캘리브레이션으로 얻은 열 파라미터를 메모리에 보관해, 시뮬레이션 도구가
 * {@code profileId} 하나로 재사용할 수 있게 한다({@code TrafficDataService}와 같은
 * "서버가 최신 관측을 들고 있는" 패턴).
 *
 * <p>영속화하지 않는다 — 서버를 재시작하면 사라진다. R&E 실험 기간 동안의 실측
 * 원본은 어차피 CSV로 남겨야 하고(문서 §로그 스키마), 여기 있는 건 그 파생값이라
 * 다시 만들 수 있기 때문이다. 대신 개수 상한을 둬서 무한히 쌓이지 않게 한다.
 */
@Component
public class EdgeThermalProfileStore {

    /** 보관 상한 — 넘으면 가장 오래된 것부터 버린다. */
    static final int MAX_PROFILES = 200;

    public record Profile(String profileId, String label, BoardType board,
                          ThermalCalibrator.ThermalOverride override, long createdAtEpochMs) {}

    private final AtomicInteger seq = new AtomicInteger(1);
    private final Map<String, Profile> profiles = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Profile> eldest) {
            return size() > MAX_PROFILES;
        }
    };

    public synchronized Profile save(String label, BoardType board, ThermalCalibrator.ThermalOverride o) {
        String id = String.format("cal-%03d", seq.getAndIncrement());
        Profile p = new Profile(id, label, board, o, System.currentTimeMillis());
        profiles.put(id, p);
        return p;
    }

    public synchronized Profile get(String id) { return profiles.get(id); }

    public synchronized List<Profile> all() { return List.copyOf(profiles.values()); }
}
