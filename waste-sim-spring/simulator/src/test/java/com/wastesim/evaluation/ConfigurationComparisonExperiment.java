package com.wastesim.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.model.SimulationConfig;
import com.wastesim.pes.*;
import com.wastesim.service.*;
import com.wastesim.site.CollectionSiteRegistry;
import com.wastesim.simulation.SimulationEngine;
import com.wastesim.template.*;
import com.wastesim.tool.SimulationConfigValidator;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly invoked experiment, excluded from the default *Test suite. No live scenario is touched. */
public class ConfigurationComparisonExperiment {
    record Case(String id, String description, Map<String,Object> changes, String expected) {}
    private final ObjectMapper json=new ObjectMapper();
    private final TemplateCatalog catalog=new TemplateCatalog();
    private final SubtaskPlanner planner=new SubtaskPlanner(catalog,new AnswerNormalizer());
    private final PesFlattener flattener=new PesFlattener(catalog);
    private final SimulationConfigValidator validator=new SimulationConfigValidator(new TrafficDataService(),CollectionSiteRegistry.empty());
    private final ScenarioBuilder builder=new ScenarioBuilder(flattener,new PesBackVerifier(catalog),validator,planner);
    private final SimulationService service=new SimulationService(new SimulationEngine(new TrafficDataService()));

    private Map<String,Object> preset() {
        Map<String,Object> v=new LinkedHashMap<>();
        v.put("truckType","SMALL_1TON");v.put("truckCount",1);v.put("numBuildings",4);v.put("residentsPerBuilding",25);
        v.put("days",2);v.put("seeds",1);v.put("trafficMode","IGNORE");v.put("travelTimeMode","LEGACY_CONSTANT");
        v.put("occupationMix",List.of("Student"));v.put("routeAvailableCapacityKg",150);
        v.put("binCapacityKg",30.0);v.put("complaintThreshold",0.8);v.put("dischargeTimeMode","PAPER_BASELINE");
        v.put("collectionTimeMinutes",720);
        return v;
    }
    private List<Case> cases() { return List.of(
        new Case("C01","기본 단일 차량",Map.of(),"READY"),
        new Case("C02","복수 차량인데 배차 간격 미제시",Map.of("truckCount",2),"NEEDS_INPUT"),
        new Case("C03","복수 차량과 배차 간격 명시",Map.of("truckCount",2,"dispatchIntervalMinutes",10),"READY"),
        new Case("C04","단일 차량에 적용되지 않는 배차 간격 명시",Map.of("dispatchIntervalMinutes",10),"INVALID"),
        new Case("C05","건물 하나에서는 배차 간격 면제",Map.of("numBuildings",1,"truckCount",2),"READY"),
        new Case("C06","상수 이동에 적용되지 않는 구역 배정 명시",Map.of("zoneAssignmentRule","ROUND_ROBIN"),"INVALID"),
        new Case("C07","상수 이동에 적용되지 않는 구역 내 이동시간 명시",Map.of("intraZoneTravelMinutes",3),"INVALID"),
        new Case("C08","교통 반영인데 프로파일 미제시",Map.of("trafficMode","APPLY"),"NEEDS_INPUT"),
        new Case("C09","교통 반영과 프로파일 명시",Map.of("trafficMode","APPLY","trafficProfileId","jangryang-weekday"),"READY"),
        new Case("C10","8개 건물 구역 이동에서 배정 규칙 미제시",Map.of("numBuildings",8,"travelTimeMode","ZONE_PROXY_HYBRID"),"NEEDS_INPUT"),
        new Case("C11","연속 배정에서 구역 내 이동시간 미제시",Map.of("numBuildings",8,"travelTimeMode","ZONE_PROXY_HYBRID","zoneAssignmentRule","CONTIGUOUS"),"NEEDS_INPUT"),
        new Case("C12","순환 배정으로 구역 이동 구성",Map.of("numBuildings",8,"travelTimeMode","ZONE_PROXY_HYBRID","zoneAssignmentRule","ROUND_ROBIN"),"READY"),
        new Case("C13","연속 배정과 구역 내 이동시간 명시",Map.of("numBuildings",8,"travelTimeMode","ZONE_PROXY_HYBRID","zoneAssignmentRule","CONTIGUOUS","intraZoneTravelMinutes",3),"READY"),
        new Case("C14","건물 0개 범위 위반",Map.of("numBuildings",0),"INVALID"),
        new Case("C15","기간 0일 범위 위반",Map.of("days",0),"INVALID"),
        new Case("C16","수거 시각 범위 위반",Map.of("collectionTimeMinutes",1500),"INVALID"),
        new Case("C17","교통 미반영에 남은 프로파일 입력",Map.of("trafficProfileId","jangryang-weekday"),"INVALID")
    ); }
    private String manualRules(Map<String,Object> v, Set<String> explicit, String baseStatus) {
        boolean dispatch=((Number)v.get("truckCount")).intValue()>=2 && ((Number)v.get("numBuildings")).intValue()>=2;
        boolean traffic=v.get("trafficMode").equals("APPLY");
        boolean zone=v.get("travelTimeMode").equals("ZONE_PROXY_HYBRID") && ((Number)v.get("numBuildings")).intValue()>4;
        boolean intra=zone && "CONTIGUOUS".equals(v.get("zoneAssignmentRule"));
        var active=Map.of("dispatchIntervalMinutes",dispatch,"trafficProfileId",traffic,"zoneAssignmentRule",zone,"intraZoneTravelMinutes",intra);
        if (active.entrySet().stream().anyMatch(e->!e.getValue() && explicit.contains(e.getKey()))) return "INVALID";
        if (active.entrySet().stream().anyMatch(e->e.getValue() && !v.containsKey(e.getKey()))) return "NEEDS_INPUT";
        return baseStatus;
    }
    private Map<String,Object> metrics(SimulationConfig cfg) {
        var r=service.runExperiment(cfg);
        return Map.of("meanComplaints",r.getMeanComplaints(),"avgRouteDurationMinutes",r.getAvgCompletionMinutes(),
                "residualWasteKg",r.getResidualWasteKg(),"massBalanceErrorKg",r.getMassBalanceErrorKg());
    }
    @Test public void compareFixedPresetManualDependenciesAndSes() throws Exception {
        List<Map<String,Object>> rows=new ArrayList<>();
        for (Case c:cases()) {
            var values=preset(); values.putAll(c.changes());
            Map<String,String> origins=new LinkedHashMap<>();
            values.keySet().forEach(k->origins.put(k,c.changes().containsKey(k)?"USER":"MODEL_DEFAULT"));
            var pes=new Pes(catalog.sesId(),catalog.sesVersion(),values,origins);
            String fixedStatus="INVALID"; SimulationConfig fixed=null; List<String> fixedErrors=new ArrayList<>();
            try {
                fixed=flattener.flatten(pes);
                var valid=validator.validate(fixed);
                fixedStatus=valid.ready()?"READY":"INVALID";
                valid.errors().forEach(e->fixedErrors.add(e.message()));
            } catch (RuntimeException e) { fixedErrors.add(e.getMessage()); }
            String manualStatus=manualRules(values,c.changes().keySet(),fixedStatus);
            var plan=planner.plan(values);
            boolean malformed=plan.instances().stream().anyMatch(i->i.status()==SubtaskStatus.UNFILLED && i.reason()!=null);
            boolean stale=plan.instances().stream().anyMatch(i->i.status()==SubtaskStatus.NOT_GENERATED && c.changes().containsKey(i.answerKey()));
            String sesStatus=malformed||stale?"INVALID":!plan.complete()?"NEEDS_INPUT":"READY";
            List<String> sesErrors=new ArrayList<>(); Scenario scenario=null;
            try {
                var input=new LinkedHashMap<>(values); Object slot=input.remove("collectionTimeMinutes");
                scenario=builder.build(new Pes(catalog.sesId(),catalog.sesVersion(),input,origins),
                        new ExperimentFrame("collectionTimeMinutes",List.of(slot),List.of("meanComplaints")));
                sesErrors.addAll(scenario.blocks());sesErrors.addAll(scenario.branchBlocks());sesErrors.addAll(scenario.backVerificationBlocks());
                if (sesStatus.equals("READY") && !scenario.valid()) sesStatus="INVALID";
            } catch (RuntimeException e) { sesErrors.add(e.getMessage());sesStatus="INVALID"; }
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("id",c.id());row.put("description",c.description());row.put("changes",c.changes());row.put("expected",c.expected());
            row.put("fixedPreset",fixedStatus);row.put("fixedWithManualRules",manualStatus);row.put("ses",sesStatus);
            row.put("fixedErrors",fixedErrors);row.put("sesErrors",sesErrors);
            row.put("selectedDecisions",plan.instances().stream().filter(i->i.status()!=SubtaskStatus.NOT_GENERATED).map(SubtaskInstance::answerKey).toList());
            if (fixedStatus.equals("READY")) row.put("fixedMetrics",metrics(fixed));
            if (sesStatus.equals("READY") && scenario!=null) row.put("sesMetrics",metrics(scenario.runs().get(0)));
            if (row.containsKey("fixedMetrics") && row.containsKey("sesMetrics"))
                row.put("identicalOutputs",row.get("fixedMetrics").equals(row.get("sesMetrics")));
            rows.add(row);
        }
        Map<String,Object> summary=new LinkedHashMap<>();
        for (String method:List.of("fixedPreset","fixedWithManualRules","ses")) {
            summary.put(method,Map.of("correct",rows.stream().filter(r->r.get(method).equals(r.get("expected"))).count(),
                    "falseReady",rows.stream().filter(r->r.get(method).equals("READY")&&!r.get("expected").equals("READY")).count(),
                    "cases",rows.size()));
        }
        var report=Map.of("scope","17 provider-constraint fixtures; same engine, seed=1, preset and range validator; no LLM", "summary",summary,"cases",rows);
        Path out=Path.of(System.getProperty("evaluation.output","../docs/reports/model-evaluation-2026-11-03"));
        Files.createDirectories(out);
        Files.writeString(out.resolve("configuration-comparison.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n");
        System.out.println(json.writeValueAsString(summary));
        assertTrue(rows.stream().allMatch(r->r.get("ses").equals(r.get("expected"))),"SES differs from preregistered expected outcomes; see artifact");
        assertTrue(rows.stream().filter(r->r.containsKey("identicalOutputs")).allMatch(r->Boolean.TRUE.equals(r.get("identicalOutputs"))),"Shared valid settings must yield identical outputs");
    }
}
