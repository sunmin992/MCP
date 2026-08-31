package com.wastesim.subtask;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 버전별 서브태스크 세트를 보관·조회한다(SDD 2.18.2).
 * {@code TrafficDataService}·{@code AiLoadProfileService}와 같은 리소스 로딩 패턴이다.
 *
 * <p><b>이 클래스에 LLM 호출이 한 줄도 없다는 점이 설계의 핵심이다</b>(D-44, NFR-17).
 * 질문 문장이 어디서 오는지를 묻는다면 답은 항상 리소스 파일이고, 그 사이에 모델이
 * 끼어들 자리가 없다. 그래서 백엔드를 Qwen에서 Llama로 바꿔도 사용자가 받는 질문이
 * 같다는 것이 "그렇게 프롬프트했다"가 아니라 <b>구조적으로</b> 참이다(UT-301).
 *
 * <p><b>왜 리소스 파일인가</b>: 질문을 자바 상수로 두면 문구 하나 고치는 데 재컴파일이
 * 필요하고, 반대로 DB에 두면 아무도 모르게 바뀔 수 있다. 리소스 파일은 그 중간이다 —
 * 코드 변경 없이 고칠 수 있지만, 고치는 순간 해시가 달라져 고정성 테스트(UT-299)가
 * 깨진다. 즉 <b>버전을 올리지 않고는 못 고친다</b>(D-45).
 *
 * <p>세트가 하나라도 규약을 어기면(항목 누락·order 중복 등) 기동을 실패시킨다. 잘못된
 * 세트로 서버가 뜨면 그 결함은 사용자가 답을 다 채운 뒤에야 드러난다.
 */
@Component
public class JangnyangSubtaskCatalog {

    /** 세트 리소스 경로. 버전을 올릴 때는 이 배열에 <b>새 파일을 추가</b>한다(덮어쓰지 않는다, D-45). */
    private static final String[] SET_RESOURCES = {
            "/subtask/jangnyang-simulator-v2.json"
    };

    /** 등록 순서를 유지한다 — 목록 출력이 매번 같은 순서여야 한다. */
    private final Map<Integer, JangnyangSubtaskDefinition> byVersion = new LinkedHashMap<>();

    public JangnyangSubtaskCatalog() {
        this(SET_RESOURCES);
    }

    /** 테스트가 다른 세트 파일을 끼워 넣을 수 있게 열어 둔 생성자. */
    JangnyangSubtaskCatalog(String... resources) {
        ObjectMapper mapper = new ObjectMapper();
        for (String path : resources) {
            try (InputStream in = getClass().getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("서브태스크 세트 리소스를 찾을 수 없다: " + path);
                }
                JangnyangSubtaskDefinition def = mapper.readValue(in, JangnyangSubtaskDefinition.class);
                verify(def, path);
                byVersion.put(def.version(), def);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("서브태스크 세트 로드 실패: " + path, e);
            }
        }
        if (byVersion.isEmpty()) {
            throw new IllegalStateException("서브태스크 세트가 하나도 없다 — 구성 계층을 쓸 수 없다");
        }
    }

    /**
     * 세트가 FR-120의 규약을 지키는지 확인한다. 실패하면 기동을 멈춘다.
     */
    private static void verify(JangnyangSubtaskDefinition def, String path) {
        if (def.subtasks().isEmpty()) {
            throw new IllegalStateException("서브태스크가 하나도 없다: " + path);
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Set<Integer> orders = new java.util.HashSet<>();
        java.util.Set<String> fields = new java.util.HashSet<>();
        for (JangnyangSubtask s : def.subtasks()) {
            if (!s.isFullySpecified()) {
                throw new IllegalStateException(
                        "서브태스크에 빠진 항목이 있다(FR-120): " + s.id() + " in " + path);
            }
            if (!ids.add(s.id())) {
                throw new IllegalStateException("서브태스크 ID가 중복이다: " + s.id() + " in " + path);
            }
            if (!orders.add(s.order())) {
                throw new IllegalStateException("서브태스크 order가 중복이다: " + s.order() + " in " + path);
            }
            if (!fields.add(s.answerField())) {
                throw new IllegalStateException(
                        "답변 필드명이 중복이다: " + s.answerField() + " in " + path);
            }
            if (s.answerType() == AnswerType.ENUM && s.allowedRange().valuesOrEmpty().isEmpty()) {
                throw new IllegalStateException(
                        "ENUM 서브태스크에 허용 값 목록이 없다: " + s.id() + " in " + path);
            }
        }
        // 모든 질문이 실재하는 단계에 속해야 한다 — 단계가 없으면 사용자 화면에 띄울
        // 자리가 없고, 진행 표시("3/8")의 분모도 맞지 않는다.
        for (SubtaskGroup g : def.groups()) {
            if (!g.isFullySpecified()) {
                throw new IllegalStateException("단계 정의가 불완전하다: " + g.order() + " in " + path);
            }
        }
        for (JangnyangSubtask s : def.subtasks()) {
            if (def.group(s.group()) == null) {
                throw new IllegalStateException(
                        "존재하지 않는 단계를 가리킨다: " + s.id() + " → group " + s.group() + " in " + path);
            }
        }

        // order 수열은 1부터 빈틈 없이 이어져야 한다 — 중간이 비면 진행률(FR-128)이
        // "3/19 중 5번째"처럼 말이 안 되는 값을 내게 된다.
        for (int i = 1; i <= def.subtasks().size(); i++) {
            if (!orders.contains(i)) {
                throw new IllegalStateException("서브태스크 order 수열에 빈틈이 있다: " + i + " in " + path);
            }
        }
    }

    /**
     * 버전으로 세트를 조회한다.
     *
     * @return 해당 버전의 세트. <b>없으면 {@code null}</b> — 가까운 버전으로 대체하지
     *         않는다(FR-138·UT-300). 조용히 다른 버전을 주면 진행 중인 세션이 어떤
     *         질문을 받았는지 사후에 재구성할 수 없다(NFR-20).
     */
    public JangnyangSubtaskDefinition byVersion(int version) {
        return byVersion.get(version);
    }

    /** 최신(가장 높은 번호) 버전 — 버전을 지정하지 않은 조회의 기본값. */
    public JangnyangSubtaskDefinition latest() {
        return byVersion.values().stream()
                .max(java.util.Comparator.comparingInt(JangnyangSubtaskDefinition::version))
                .orElseThrow();
    }

    /** 등록된 버전 번호 전체(오름차순). */
    public List<Integer> versions() {
        return byVersion.keySet().stream().sorted().toList();
    }
}
