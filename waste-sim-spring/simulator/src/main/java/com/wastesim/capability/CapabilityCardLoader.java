package com.wastesim.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 능력 카드를 읽고, 자연어 구절을 미지원 항목에 대조한다.
 *
 * <p>대조는 <b>부분 문자열 포함</b>이다. 형태소 분석을 쓰지 않는 이유는 매칭 키를
 * 제공자가 직접 적기 때문이다 — 분석기가 못 자르는 표현이 나오면 키를 한 줄 더 적으면
 * 되고, 그 편이 왜 매칭됐는지 설명하기도 쉽다.
 */
@Component
public class CapabilityCardLoader {

    private static final String RESOURCE = "/mcp/jangnyang-capability-card.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode raw;
    private final CapabilityCard card;

    public CapabilityCardLoader() {
        this(RESOURCE);
    }

    /**
     * 리소스 경로를 지정하는 생성자. 패키지 밖에는 열지 않는다 — 운영 코드는 항상
     * 기본 경로({@link #RESOURCE})만 쓰고, 이 생성자는 "리소스가 없으면 예외를
     * 던진다"는 조용한 폴백 금지 제약을 테스트가 직접 확인할 수 있게 하기 위한
     * 최소한의 통로다.
     */
    CapabilityCardLoader(String resourcePath) {
        try (InputStream in = CapabilityCardLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("능력 카드 리소스가 없습니다: " + resourcePath);
            }
            this.raw = mapper.readTree(in);
            this.card = mapper.treeToValue(raw, CapabilityCard.class);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("능력 카드를 읽지 못했습니다: " + resourcePath, e);
        }
    }

    public CapabilityCard card() {
        return card;
    }

    /** 도구가 그대로 내보내는 원문. 자바 모델이 덮지 못한 칸까지 전달한다. */
    public JsonNode rawJson() {
        return raw;
    }

    /**
     * 구절 하나를 대조한다. 여러 항목이 걸리면 <b>매칭된 키가 가장 긴</b> 항목을 고른다 —
     * "실제 도로 기반 이동시간"이 "실제 도로"와 "도로" 양쪽에 걸릴 때 더 구체적인 쪽을
     * 고르기 위해서다.
     */
    public Optional<UnsupportedItem> matchOne(String phrase) {
        if (phrase == null || phrase.isBlank()) return Optional.empty();
        String haystack = phrase.toLowerCase();
        UnsupportedItem best = null;
        int bestLength = -1;
        for (UnsupportedItem item : card.unsupported()) {
            for (String key : item.matchKeys()) {
                if (haystack.contains(key.toLowerCase()) && key.length() > bestLength) {
                    best = item;
                    bestLength = key.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** 여러 구절을 대조해 걸린 항목을 중복 없이 돌려준다. */
    public List<UnsupportedItem> matchAll(Collection<String> phrases) {
        List<UnsupportedItem> hits = new ArrayList<>();
        for (String phrase : phrases) {
            matchOne(phrase).ifPresent(item -> {
                if (hits.stream().noneMatch(h -> h.code().equals(item.code())
                        && h.capability().equals(item.capability()))) {
                    hits.add(item);
                }
            });
        }
        return List.copyOf(hits);
    }
}
