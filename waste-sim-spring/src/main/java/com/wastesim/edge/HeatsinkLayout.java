package com.wastesim.edge;

import java.util.List;

/**
 * 방열판 한 후보의 "형상 + 배치 + 기류" 전체 명세. 학생이 실제로 손으로 바꿀 수 있는
 * 것만 필드로 뒀다 — 방열판 크기·핀 개수/높이·재질, SoC 중심 대비 위치 오프셋,
 * 핀 방향, 열전달물질(TIM), 팬 유무·거리.
 *
 * <p>좌표계: SoC 패키지 중심이 원점(0,0), 단위 mm. +X는 보드 긴 변, +Y는 짧은 변
 * 방향으로 학생이 일관되게만 정하면 된다(열화상 사진에 자를 같이 찍어 좌표를 읽는 것을 권장).
 *
 * @param name      후보 이름(비교 결과에서 이 이름으로 랭킹된다)
 * @param heatsink  방열판 형상
 * @param placement 배치
 * @param airflow   기류 조건
 * @param tim       접촉 열전달물질
 * @param hotspots  SoC 외 부수 발열점(열화상으로 찍어 좌표를 넣는다). 비워도 된다
 */
public record HeatsinkLayout(
        String name,
        Heatsink heatsink,
        Placement placement,
        Airflow airflow,
        Tim tim,
        List<Hotspot> hotspots) {

    /** 방열판 재질과 열전도율(W/m·K). */
    public enum Material {
        ALUMINUM(205.0), COPPER(385.0);
        private final double k;
        Material(double k) { this.k = k; }
        public double conductivity() { return k; }
        public static Material parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toUpperCase()) {
                case "ALUMINUM", "ALUMINIUM", "AL", "알루미늄" -> ALUMINUM;
                case "COPPER", "CU", "구리" -> COPPER;
                default -> null;
            };
        }
    }

    /**
     * 핀이 지배적 기류 방향(강제대류=팬 바람, 자연대류=중력 상승 방향)과 나란한지.
     * 같은 방열판이라도 90° 돌려 붙이면 성능이 크게 달라지는 것이 이 실험의 핵심 관찰
     * 포인트 중 하나다.
     */
    public enum FinAlignment {
        /** 핀 채널이 기류와 나란함(권장). */
        ALIGNED,
        /** 핀 채널이 기류를 가로막음. */
        CROSS;
        public static FinAlignment parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toUpperCase().replace("-", "_")) {
                case "ALIGNED", "PARALLEL", "나란함", "평행" -> ALIGNED;
                case "CROSS", "PERPENDICULAR", "수직", "가로" -> CROSS;
                default -> null;
            };
        }
    }

    public enum AirflowType {
        /** 자연대류(팬 없음). */
        NATURAL,
        /** 강제대류(팬). */
        FORCED;
        public static AirflowType parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toUpperCase()) {
                case "NATURAL", "NONE", "자연대류" -> NATURAL;
                case "FORCED", "FAN", "강제대류" -> FORCED;
                default -> null;
            };
        }
    }

    /** 열전달물질 종류별 기본 열전도율(W/m·K) — 실측값이 있으면 conductivityWmK로 덮어쓴다. */
    public enum TimType {
        /** 실리콘 서멀패드. */
        PAD(3.0, 0.5),
        /** 서멀 그리스/페이스트. */
        PASTE(8.5, 0.08),
        /** 양면 접착 서멀테이프(라즈베리파이 기본 방열판 동봉품). */
        TAPE(0.9, 0.2);
        private final double k;
        private final double defaultThicknessMm;
        TimType(double k, double thickness) { this.k = k; this.defaultThicknessMm = thickness; }
        public double conductivity() { return k; }
        public double defaultThicknessMm() { return defaultThicknessMm; }
        public static TimType parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toUpperCase()) {
                case "PAD", "THERMAL_PAD", "패드" -> PAD;
                case "PASTE", "GREASE", "서멀구리스", "그리스" -> PASTE;
                case "TAPE", "ADHESIVE", "테이프" -> TAPE;
                default -> null;
            };
        }
    }

    /**
     * @param baseLengthMm    베이스 길이(mm) — 핀이 뻗은 방향의 길이
     * @param baseWidthMm     베이스 폭(mm) — 핀이 나열된 방향
     * @param baseThicknessMm 베이스 두께(mm)
     * @param finCount        핀 개수(0이면 민판 방열판)
     * @param finHeightMm     핀 높이(mm)
     * @param finThicknessMm  핀 두께(mm)
     * @param material        재질
     */
    public record Heatsink(double baseLengthMm, double baseWidthMm, double baseThicknessMm,
                           int finCount, double finHeightMm, double finThicknessMm,
                           Material material) {}

    /**
     * @param offsetXMm     방열판 베이스 중심의 SoC 중심 대비 X 오프셋(mm)
     * @param offsetYMm     Y 오프셋(mm)
     * @param finAlignment  핀 방향
     */
    public record Placement(double offsetXMm, double offsetYMm, FinAlignment finAlignment) {}

    /**
     * @param type          자연/강제 대류
     * @param airSpeedMps   방열판 표면 풍속(m/s). 0이면 fanRpm에서 추정
     * @param fanRpm        팬 회전수. airSpeedMps가 없을 때 풍속 추정에 쓴다
     * @param fanDistanceMm 팬-방열판 거리(mm) — 멀수록 유효 풍속이 감쇠한다
     */
    public record Airflow(AirflowType type, double airSpeedMps, double fanRpm, double fanDistanceMm) {}

    /**
     * @param type            TIM 종류
     * @param thicknessMm     두께(mm) — 두꺼울수록 접촉 열저항이 커진다
     * @param conductivityWmK 열전도율(W/m·K). 0이면 type 기본값
     */
    public record Tim(TimType type, double thicknessMm, double conductivityWmK) {
        public double effectiveConductivity() {
            return conductivityWmK > 0 ? conductivityWmK : type.conductivity();
        }
    }

    /**
     * SoC 외 부수 발열점(PMIC, USB 컨트롤러, 무선칩 등). 열화상 카메라로 위치를 읽어 넣는다.
     *
     * @param name   이름(예: "PMIC", "USB_VL805")
     * @param xMm    SoC 중심 대비 X(mm)
     * @param yMm    SoC 중심 대비 Y(mm)
     * @param powerW 추정 발열(W)
     */
    public record Hotspot(String name, double xMm, double yMm, double powerW) {}
}
