package com.wastesim.controller;

import com.wastesim.service.TmapRouteService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 기존 시뮬레이션을 건드리지 않고 TMAP 값을 확인하는 임시 비교 API
 * ({@code OsrmTestController}와 같은 성격).
 *
 * <p>응답에 {@code trafficIncluded: true}와 {@code requestedAt}을 반드시 싣는다. 앞의 것은
 * 이 값이 OSRM 자유주행시간과 성질이 달라 <b>같은 자리에 넣으면 안 된다</b>는 표시이고,
 * 뒤의 것은 교통 반영값이 <b>언제 기준인지</b> 없으면 나중에 대조할 수 없기 때문이다.
 */
@RestController
@RequestMapping("/api/traffic/tmap")
public class TmapTestController {

    private final TmapRouteService tmap;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TmapTestController(TmapRouteService tmap) {
        this(tmap, Clock.systemUTC());
    }

    /** 테스트가 조회 시각을 고정할 때 쓰는 생성자. */
    TmapTestController(TmapRouteService tmap, Clock clock) {
        this.tmap = tmap;
        this.clock = clock;
    }

    @PostMapping("/route")
    public ResponseEntity<?> route(@RequestBody(required = false) RouteRequest request) {
        if (!tmap.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "TMAP_DISABLED",
                    "message", "실험 기능을 사용하려면 tmap.enabled=true로 실행하세요."));
        }
        if (!tmap.hasAppKey()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "TMAP_APP_KEY_MISSING",
                    "message", "TMAP App Key가 없습니다. TMAP_APP_KEY 환경변수로 주입하세요."));
        }
        try {
            TmapRouteService.Point start = point(request, true);
            TmapRouteService.Point end = point(request, false);
            Instant at = clock.instant();
            TmapRouteService.RouteResult r = tmap.route(start, end);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("source", "TMAP");
            body.put("profile", "car");
            body.put("from", start.name());
            body.put("to", end.name());
            body.put("totalDistanceMeters", round(r.distanceMeters(), 1));
            body.put("totalDurationSeconds", round(r.durationSeconds(), 1));
            body.put("totalDurationMinutes", round(r.durationSeconds() / 60.0, 2));
            body.put("trafficIncluded", true);
            body.put("requestedAt", at.toString());
            body.put("note", "TMAP totalTime은 조회 시각의 교통 상황이 이미 반영된 값입니다. "
                    + "OSRM 자유주행시간이 들어가는 자리(OSRM_HYBRID)에 넣으면 혼잡을 두 번 셉니다. "
                    + "또한 승용차 기준이라 5톤 화물차의 통행 제한을 반영하지 않습니다.");
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_ARGUMENTS", "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "TMAP_DISABLED", "message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "TMAP_UNAVAILABLE", "message", e.getMessage()));
        }
    }

    private static TmapRouteService.Point point(RouteRequest r, boolean isStart) {
        if (r == null) throw new IllegalArgumentException("start와 end가 필요합니다.");
        Waypoint w = isStart ? r.start() : r.end();
        String label = isStart ? "start" : "end";
        if (w == null || w.longitude() == null || w.latitude() == null) {
            throw new IllegalArgumentException(label + "에 name·longitude·latitude가 필요합니다.");
        }
        String name = w.name() == null || w.name().isBlank() ? label : w.name();
        return new TmapRouteService.Point(name, w.longitude(), w.latitude());
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    public record Waypoint(String name, Double longitude, Double latitude) {}
    public record RouteRequest(Waypoint start, Waypoint end) {}
}
