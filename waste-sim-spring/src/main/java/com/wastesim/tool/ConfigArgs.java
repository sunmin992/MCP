package com.wastesim.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.model.SimulationConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 도구 인자(JSON) → SimulationConfig 매핑.
 * collectionTime("HH:MM")을 collectionTimeMinutes로 변환하는 등, 도구 스키마와
 * 내부 DTO의 필드명 차이를 흡수한다.
 */
public final class ConfigArgs {

    private ConfigArgs() {}

    public static SimulationConfig fromJson(JsonNode p) {
        SimulationConfig c = new SimulationConfig();
        if (p == null || p.isNull() || !p.isObject()) return c;

        if (p.hasNonNull("collectionTime")) c.setCollectionTimeLabel(p.get("collectionTime").asText());
        if (p.has("days"))                 c.setDays(p.get("days").asInt(30));
        if (p.has("seeds"))                c.setSeeds(p.get("seeds").asInt(30));
        if (p.has("leaveSigma"))           c.setLeaveSigma(p.get("leaveSigma").asDouble(30.0));
        if (p.has("wasteSigma"))           c.setWasteSigma(p.get("wasteSigma").asDouble(0.3));
        if (p.has("capacity"))             c.setCapacity(p.get("capacity").asDouble(30.0));
        if (p.has("threshold"))            c.setThreshold(p.get("threshold").asDouble(0.8));
        if (p.has("numBuildings"))         c.setNumBuildings(p.get("numBuildings").asInt(4));
        if (p.has("residentsPerBuilding")) c.setResidentsPerBuilding(p.get("residentsPerBuilding").asInt(25));

        if (p.has("occupationMix") && p.get("occupationMix").isArray()) {
            List<String> mix = new ArrayList<>();
            for (JsonNode n : p.get("occupationMix")) mix.add(n.asText());
            if (!mix.isEmpty()) c.setOccupationMix(mix);
        }

        // ── 교통 레이어 (TRAFFIC_EXTENSION_DESIGN.md §3, §6.1) ──────────────
        if (p.has("trafficEnabled"))       c.setTrafficEnabled(p.get("trafficEnabled").asBoolean(false));
        if (p.hasNonNull("trafficProfileId")) c.setTrafficProfileId(p.get("trafficProfileId").asText());
        if (p.hasNonNull("truckType"))     c.setTruckType(p.get("truckType").asText());
        if (p.has("truckCount"))           c.setTruckCount(p.get("truckCount").asInt(1));
        if (p.has("dispatchIntervalMinutes")) c.setDispatchIntervalMinutes(p.get("dispatchIntervalMinutes").asInt(0));
        if (p.has("routeTravelMinutes"))   c.setRouteTravelMinutes(p.get("routeTravelMinutes").asInt(0));
        if (p.has("trafficComplaintWeight"))  c.setTrafficComplaintWeight(p.get("trafficComplaintWeight").asDouble(1.0));
        if (p.has("routeSequence") && p.get("routeSequence").isArray()) {
            List<String> seq = new ArrayList<>();
            for (JsonNode n : p.get("routeSequence")) seq.add(n.asText());
            if (!seq.isEmpty()) c.setRouteSequence(seq);
        }
        return c;
    }
}
