package com.districtsim.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 2026년 공휴일 달력. 기동 시 한 번 읽고, 없으면 기동을 실패시킨다 — 공휴일을 반영하라고 했는데 조용히
 * 빈 달력으로 돌면 반영하지 않은 결과가 반영한 결과로 읽힌다.
 */
@Component
public class HolidayCalendar {

    private static final String RESOURCE = "/schedule/kr-holidays-2026.json";

    private final Map<LocalDate, String> holidays = new LinkedHashMap<>();

    public HolidayCalendar() {
        try (InputStream in = HolidayCalendar.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("공휴일 달력이 없습니다: " + RESOURCE);
            JsonNode root = new ObjectMapper().readTree(in);
            for (JsonNode h : root.path("holidays")) {
                holidays.put(LocalDate.parse(h.path("date").asText()), h.path("name").asText());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("공휴일 달력을 읽지 못했습니다: " + RESOURCE, e);
        }
    }

    public boolean isHoliday(LocalDate date) {
        return holidays.containsKey(date);
    }

    public Optional<String> name(LocalDate date) {
        return Optional.ofNullable(holidays.get(date));
    }

    /** 달력이 다루는 해. 기간이 이 해를 벗어나면 그 날들은 공휴일 없음으로 계산된다. */
    public int year() {
        return 2026;
    }
}
