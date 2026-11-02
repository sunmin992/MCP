package com.wastesim.plan;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

/**
 * 단계 사건을 {@code plan-history-YYYY-MM-DD.jsonl} 에 한 줄씩 덧붙인다. 메모리 기록은 재시작하면 사라지므로
 * 평가 집계는 이 파일로 한다.
 *
 * <p>쓰기 실패는 경고만 남긴다 — 기록이 끊겼다고 사용자의 요청을 실패시킬 이유는 없다.
 */
@Component
public class PlanHistoryWriter {

    private static final Logger log = LoggerFactory.getLogger(PlanHistoryWriter.class);

    private final Path dir;

    @Autowired
    public PlanHistoryWriter(@Value("${plan.history.dir:logs/plan-history}") String dir) {
        this(Path.of(dir));
    }

    /** 시험용. */
    public PlanHistoryWriter(Path dir) {
        this.dir = dir;
    }

    /** {@code ts} 의 날짜(UTC)로 파일을 고른다. JSON 문자열 안의 줄바꿈은 이스케이프되므로 한 사건은 한 줄이다. */
    public synchronized void write(ObjectNode event) {
        String ts = event.path("ts").asText("");
        String day = ts.length() >= 10 ? ts.substring(0, 10) : "unknown";
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("plan-history-" + day + ".jsonl"), event.toString() + "\n",
                    StandardCharsets.UTF_8, CREATE, APPEND);
        } catch (IOException e) {
            log.warn("단계 기록을 파일에 쓰지 못했습니다({}): {}", dir, e.toString());
        }
    }
}
