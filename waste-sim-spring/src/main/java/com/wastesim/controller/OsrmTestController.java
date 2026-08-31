package com.wastesim.controller;

import com.wastesim.service.OsrmRouteService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 기존 시뮬레이션을 건드리지 않고 OSRM 값을 확인하는 임시 비교 API. */
@RestController
@RequestMapping("/api/traffic/osrm")
public class OsrmTestController {

    private static final int MAX_WAYPOINTS = 25;
    private final OsrmRouteService osrm;

    public OsrmTestController(OsrmRouteService osrm) {
        this.osrm = osrm;
    }

    @PostMapping("/route")
    public ResponseEntity<?> route(@RequestBody(required = false) RouteRequest request) {
        if (!osrm.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "OSRM_DISABLED",
                    "message", "실험 기능을 사용하려면 osrm.enabled=true로 실행하세요."));
        }
        try {
            List<OsrmRouteService.Waypoint> waypoints = validate(request);
            OsrmRouteService.RouteResult result = osrm.route(waypoints);

            List<Map<String, Object>> legs = new ArrayList<>();
            for (OsrmRouteService.Leg leg : result.legs()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("from", leg.from());
                row.put("to", leg.to());
                row.put("distanceMeters", round(leg.distanceMeters(), 1));
                row.put("durationSeconds", round(leg.durationSeconds(), 1));
                row.put("durationMinutes", round(leg.durationSeconds() / 60.0, 2));
                legs.add(row);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("source", "OSRM");
            body.put("profile", "driving");
            body.put("routeSequence", request.routeSequence());
            body.put("totalDistanceMeters", round(result.distanceMeters(), 1));
            body.put("totalDurationSeconds", round(result.durationSeconds(), 1));
            body.put("totalDurationMinutes", round(result.durationSeconds() / 60.0, 2));
            body.put("legs", legs);
            body.put("note", "OSRM 기본 주행시간이며 현재 장량동 시간대별 혼잡 가중치는 적용하지 않았습니다.");
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_ARGUMENTS", "message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "OSRM_UNAVAILABLE", "message", e.getMessage()));
        }
    }

    private List<OsrmRouteService.Waypoint> validate(RouteRequest request) {
        if (request == null || request.routeSequence() == null || request.coordinates() == null) {
            throw new IllegalArgumentException("routeSequence와 coordinates가 필요합니다.");
        }
        if (request.routeSequence().size() < 2 || request.routeSequence().size() > MAX_WAYPOINTS) {
            throw new IllegalArgumentException("routeSequence는 2~" + MAX_WAYPOINTS + "개 노드여야 합니다.");
        }
        List<OsrmRouteService.Waypoint> out = new ArrayList<>();
        for (String id : request.routeSequence()) {
            List<Double> point = request.coordinates().get(id);
            if (id == null || id.isBlank() || point == null || point.size() != 2) {
                throw new IllegalArgumentException(id + "의 좌표는 [경도, 위도] 형식이어야 합니다.");
            }
            Double lon = point.get(0);
            Double lat = point.get(1);
            if (lon == null || lat == null || !Double.isFinite(lon) || !Double.isFinite(lat)
                    || lon < -180 || lon > 180 || lat < -90 || lat > 90) {
                throw new IllegalArgumentException(id + "의 경도·위도 범위가 올바르지 않습니다.");
            }
            out.add(new OsrmRouteService.Waypoint(id, lon, lat));
        }
        return out;
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    public record RouteRequest(List<String> routeSequence, Map<String, List<Double>> coordinates) {}
}
