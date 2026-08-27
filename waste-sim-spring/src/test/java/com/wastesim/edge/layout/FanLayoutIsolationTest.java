package com.wastesim.edge.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 설계 D-43을 소스 스캔으로 고정한다.
 *
 * <p>{@code FanArraySpec}은 "실측 보정 전까지 위치별 냉각 차이를 만들지 않는다"고
 * 명시하는데, 이 패키지의 점수 모델은 정확히 그 차이를 만든다. 두 세계를 나누는 유일한
 * 실질적 장치가 <b>컴파일 의존성이 없다는 사실</b>이다 — 참조가 생기는 순간 임시 계수가
 * 물리 모델 결과로 흘러들 통로가 열린다.
 *
 * <p>이런 규칙은 리뷰어의 기억에 맡기면 반드시 새므로 테스트로 고정한다.
 */
class FanLayoutIsolationTest {

    /** 이 패키지가 참조하면 안 되는 열 스택 타입. */
    private static final List<String> FORBIDDEN = List.of(
            "ThermalSimulator", "HeatsinkThermalModel", "ThermalParams", "ThermalRun");

    @Test
    @DisplayName("layout 패키지가 열 시뮬레이션 스택을 참조하지 않는다 (D-43)")
    void layoutPackageDoesNotTouchThermalStack() throws IOException {
        Path dir = Path.of("src", "main", "java", "com", "wastesim", "edge", "layout");
        assertTrue(Files.isDirectory(dir), "패키지 디렉터리를 찾을 수 없다: " + dir.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                // javadoc의 {@code ...} 언급은 의존성이 아니므로 코드에서만 본다.
                String stripped = stripComments(src);
                for (String type : FORBIDDEN) {
                    if (stripped.contains(type)) {
                        violations.add(f.getFileName() + " → " + type);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "배치 점수 모델이 열 스택을 참조한다 — 임시 계수가 물리 결과로 샌다: " + violations);
    }

    @Test
    @DisplayName("FanArraySpec은 SourceStatus enum만 쓴다")
    void onlySourceStatusIsBorrowedFromFanArraySpec() throws IOException {
        Path dir = Path.of("src", "main", "java", "com", "wastesim", "edge", "layout");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String stripped = stripComments(Files.readString(f));
                int at = stripped.indexOf("FanArraySpec");
                while (at >= 0) {
                    String after = stripped.substring(at);
                    assertTrue(after.startsWith("FanArraySpec.SourceStatus")
                                    || after.startsWith("FanArraySpec;"),
                            f.getFileName() + "에서 FanArraySpec을 SourceStatus 외 용도로 쓴다");
                    at = stripped.indexOf("FanArraySpec", at + 1);
                }
            }
        }
    }

    /**
     * 블록/라인 주석과 javadoc {@code ...} 인라인 태그의 내용을 제거한다.
     *
     * <p>{@code {@code TypeName}}은 자바독 블록 주석({@code /** ... *&#47;}) 안에 있으므로
     * 블록 주석 제거만으로 충분히 걸러진다 — 별도 처리가 필요 없다. 다만 향후 자바독 밖의
     * 한 줄 {@code // ... {@code Foo} ...} 같은 형태가 생기더라도 라인 주석 제거가 먼저
     * 적용되므로 안전하다.
     */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "")
                  .replaceAll("(?m)//.*$", "");
    }
}
