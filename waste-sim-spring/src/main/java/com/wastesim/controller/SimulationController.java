package com.wastesim.controller;

import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.service.SimulationService;
import com.wastesim.tool.SimulationTool;
import com.wastesim.tool.ToolResult;
import com.wastesim.tool.ValidationResult;
import com.wastesim.web.ApiError;
import com.wastesim.web.CompareRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 시뮬레이션 REST API. 검증·실행은 SimulationTool 파사드로 위임해
 * MCP·채팅과 동일한 검증 게이트를 통과한다. 검증 실패 시 400 + ApiError.
 */
@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    private final SimulationService simulationService;
    private final SimulationTool tool;

    public SimulationController(SimulationService simulationService, SimulationTool tool) {
        this.simulationService = simulationService;
        this.tool = tool;
    }

    /** POST /api/simulation/run — 단일 시드 실행 (검증만 파사드 경유, 실행은 단일 시드) */
    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody SimulationConfig cfg,
                                 @RequestParam(defaultValue = "1") int seed) {
        // 단일 시드는 파사드의 다중 시드 실행과 별개이므로 검증만 재사용(실행 안 함)
        ValidationResult vr = tool.validate(cfg);
        if (!vr.ready()) {
            return ResponseEntity.badRequest().body(
                    ApiError.of("VALIDATION", "설정 검증 실패", vr.errors()));
        }
        SimulationResult result = simulationService.runSingle(cfg, seed);
        return ResponseEntity.ok(result);
    }

    /** POST /api/simulation/experiment — 다중 시드 실험 */
    @PostMapping("/experiment")
    public ResponseEntity<?> experiment(@RequestBody SimulationConfig cfg) {
        ToolResult tr = tool.runSimulation(cfg);
        if (!tr.ready()) {
            return ResponseEntity.badRequest().body(
                    ApiError.of("VALIDATION", "설정 검증 실패", tr.errors()));
        }
        return ResponseEntity.ok(tr.result());
    }

    /** POST /api/simulation/compare — 여러 수거 시각 비교 실험 (타입 DTO로 500 위험 제거) */
    @PostMapping("/compare")
    public ResponseEntity<?> compare(@RequestBody CompareRequest body) {
        List<Object> results = new ArrayList<>();
        for (String t : body.getTimes()) {
            SimulationConfig cfg = new SimulationConfig();
            cfg.setCollectionTimeLabel(t);
            cfg.setDays(body.getDays());
            cfg.setSeeds(body.getSeeds());
            cfg.setLeaveSigma(body.getLeaveSigma());
            ToolResult tr = tool.runSimulation(cfg);
            if (!tr.ready()) {
                return ResponseEntity.badRequest().body(
                        ApiError.of("VALIDATION", "수거시각 " + t + " 설정 검증 실패", tr.errors()));
            }
            results.add(tr.result());
        }
        return ResponseEntity.ok(results);
    }
}
