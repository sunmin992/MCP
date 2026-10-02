package com.wastesim.controller;

import com.wastesim.model.TrafficProfile;
import com.wastesim.service.TrafficDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 교통 프로파일 조회 API — UI에서 시간대별 혼잡도를 시각화할 때 사용한다. */
@RestController
@RequestMapping("/api/traffic")
public class TrafficController {

    private final TrafficDataService trafficData;

    public TrafficController(TrafficDataService trafficData) {
        this.trafficData = trafficData;
    }

    /** GET /api/traffic/default — 시스템 기본 교통 프로파일. */
    @GetMapping("/default")
    public ResponseEntity<?> defaultProfile() {
        return profile(trafficData.defaultProfileId());
    }

    /** GET /api/traffic/{id} — id로 조회. 없으면 404. */
    @GetMapping("/{id}")
    public ResponseEntity<?> profile(@PathVariable String id) {
        TrafficProfile p = trafficData.find(id);
        if (p == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(p);
    }
}
