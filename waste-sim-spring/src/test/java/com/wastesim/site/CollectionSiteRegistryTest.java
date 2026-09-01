package com.wastesim.site;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CollectionSiteRegistry}의 계약을 고정한다.
 *
 * <p>이 레지스트리의 요점은 <b>있는 것</b>이 아니라 <b>없을 때의 행동</b>이다. 좌표를 모르는
 * 지점에 대해 값을 지어내지 않고, 비어 있어도 시스템이 지금과 똑같이 동작해야 한다. 그래서
 * 아래 테스트의 절반은 “등록되지 않았을 때 무슨 일이 일어나는가”를 본다.
 *
 * <p>나머지 절반은 기동 시 검사다. 잘못된 좌표는 런타임에 조용히 무시되는 대신 기동을
 * 막아야 한다 — 조용히 무시하면 나중에 “왜 이 지점만 실제 거리가 안 붙지”를 디버깅하게
 * 되고, 그때는 어느 값이 문제였는지 알 수 없다.
 */
class CollectionSiteRegistryTest {

    // ── 비어 있을 때 ───────────────────────────────────────────────────────

    @Test
    void shippedRegistryLoadsAndIsEmptyUntilCoordinatesAreSupplied() {
        CollectionSiteRegistry reg = loaded(CollectionSiteRegistry.RESOURCE);

        assertEquals(0, reg.size(),
                "아직 좌표가 등록되지 않았습니다. 지점을 추가했다면 이 단언을 함께 갱신하세요.");
        assertTrue(reg.all().isEmpty());
    }

    @Test
    void unregisteredSiteYieldsNothingInsteadOfAGuess() {
        CollectionSiteRegistry reg = loaded(CollectionSiteRegistry.RESOURCE);

        assertEquals(Optional.empty(), reg.find("Node_A"));
        assertFalse(reg.isRegistered("Node_A"));
        // null이 들어와도 예외가 아니라 "모른다"로 답한다 — 호출부가 방어할 것을 늘리지 않는다.
        assertEquals(Optional.empty(), reg.find(null));
        assertFalse(reg.isRegistered(null));
    }

    /** 하나라도 좌표가 없으면 경로 전체가 기존 모델로 간다 — 축이 섞인 합계를 만들지 않는다. */
    @Test
    void coversAllRequiresEveryNodeOnTheRoute() throws Exception {
        CollectionSiteRegistry reg = withSites("""
                  "Node_A": {"longitude":129.3718,"latitude":36.0696,"name":"A",
                             "adminDivision":"포항시 북구 장성동(행정동 장량동)","source":"테스트","snapMeters":10.0},
                  "Node_B": {"longitude":129.3955,"latitude":36.0762,"name":"B",
                             "adminDivision":"포항시 북구 장성동(행정동 장량동)","source":"테스트","snapMeters":18.0}
                """);

        assertTrue(reg.coversAll(List.of("Node_A", "Node_B")));
        assertFalse(reg.coversAll(List.of("Node_A", "Node_C")), "Node_C는 등록되지 않았다");
        assertFalse(reg.coversAll(List.of()), "빈 경로를 '전부 덮었다'로 보면 안 된다");
        assertFalse(reg.coversAll(null));
    }

    // ── 등록된 지점 ────────────────────────────────────────────────────────

    @Test
    void registeredSiteReturnsItsCoordinateAndProvenance() throws Exception {
        CollectionSiteRegistry reg = withSites("""
                  "Node_A": {"longitude":129.3718,"latitude":36.0696,"name":"장량동 원룸 1",
                             "adminDivision":"포항시 북구 장성동(행정동 장량동)",
                             "source":"현장 확인 2026-09-01","snapMeters":12.5}
                """);

        CollectionSite s = reg.find("Node_A").orElseThrow();
        assertEquals(129.3718, s.longitude());
        assertEquals(36.0696, s.latitude());
        assertEquals("장량동 원룸 1", s.name());
        assertEquals("현장 확인 2026-09-01", s.source());
        assertEquals(12.5, s.snapMeters());
    }

    /**
     * 결정된 방침 — 서로 다른 지점 id가 같은 좌표를 갖는 것은 <b>허용</b>한다.
     *
     * <p>근거는 “한 지점에 배출구가 둘”이 아니다. 원본 모델에서 수거 지점은 건물과 1:1이라
     * 그런 경우가 없다. 실제로 일어나는 것은 <b>서로 다른 건물이 사실상 같은 자리에
     * 배출하는 것</b>이다 — 붙어 있는 원룸 두 동이 같은 골목 어귀를 쓰는 경우.
     * 금지되는 것은 같은 id의 중복뿐이고, 그건 JSON 객체 키가 막는다.
     */
    @Test
    void twoSitesMaySharePreciselyTheSameCoordinate() throws Exception {
        CollectionSiteRegistry reg = withSites("""
                  "Node_A": {"longitude":129.3718,"latitude":36.0696,"name":"앞동",
                             "adminDivision":"행정동 장량동","source":"테스트","snapMeters":10.0},
                  "Node_B": {"longitude":129.3718,"latitude":36.0696,"name":"뒷동",
                             "adminDivision":"행정동 장량동","source":"테스트","snapMeters":10.0}
                """);

        assertEquals(2, reg.size());
        assertEquals(reg.find("Node_A").orElseThrow().longitude(),
                     reg.find("Node_B").orElseThrow().longitude());
    }

    @Test
    void registrationOrderIsPreserved() throws Exception {
        CollectionSiteRegistry reg = withSites("""
                  "Node_C": {"longitude":129.3723,"latitude":36.0689,"name":"C",
                             "adminDivision":"행정동 장량동","source":"테스트","snapMeters":1.0},
                  "Node_A": {"longitude":129.3718,"latitude":36.0696,"name":"A",
                             "adminDivision":"행정동 장량동","source":"테스트","snapMeters":1.0}
                """);

        assertEquals(List.of("Node_C", "Node_A"), List.copyOf(reg.all().keySet()));
    }

    // ── 기동 시 검사 — 아래는 전부 "기동을 막아야 한다" ────────────────────

    @Test
    void rejectsIdOutsideTheEngineNodeScheme() {
        assertStartupFails("""
                  "SITE-001": {"longitude":129.3718,"latitude":36.0696,"name":"x",
                               "adminDivision":"행정동 장량동","source":"테스트","snapMeters":1.0}
                """, "Node_A~Node_Z");
    }

    @Test
    void rejectsCoordinateOutsideJangnyangArea() {
        assertStartupFails("""
                  "Node_A": {"longitude":126.9780,"latitude":37.5665,"name":"서울시청",
                             "adminDivision":"행정동 장량동","source":"테스트","snapMeters":1.0}
                """, "장량동 인근을 벗어납니다");
    }

    @Test
    void rejectsSiteOutsideJangnyangAdministrativeDivision() {
        assertStartupFails("""
                  "Node_A": {"longitude":129.3704,"latitude":36.0611,"name":"창포사거리",
                             "adminDivision":"포항시 북구 창포동(행정동 우창동)","source":"테스트","snapMeters":1.0}
                """, "장량동이 아닙니다");
    }

    /** 출처 없는 좌표는 다음 사람이 검증할 수 없다 — 맞는지 틀린지 물을 곳이 없어진다. */
    @Test
    void rejectsSiteWithoutProvenance() {
        assertStartupFails("""
                  "Node_A": {"longitude":129.3718,"latitude":36.0696,"name":"x",
                             "adminDivision":"행정동 장량동","source":"","snapMeters":1.0}
                """, "출처");
    }

    /** 300m 넘게 밀려 스냅되는 좌표의 이동시간은 요청한 위치의 값이 아니다(OsrmRouteService와 같은 기준). */
    @Test
    void rejectsSnapDistanceBeyondTheThreshold() {
        assertStartupFails("""
                  "Node_A": {"longitude":129.3718,"latitude":36.0696,"name":"x",
                             "adminDivision":"행정동 장량동","source":"테스트","snapMeters":301.0}
                """, "스냅 거리");
    }

    @Test
    void rejectsNonNumericCoordinate() {
        assertStartupFails("""
                  "Node_A": {"longitude":"동쪽","latitude":36.0696,"name":"x",
                             "adminDivision":"행정동 장량동","source":"테스트","snapMeters":1.0}
                """, "유한한 숫자");
    }

    @Test
    void rejectsMissingResource() {
        CollectionSiteRegistry reg = new CollectionSiteRegistry("/collection/없는-파일.json");
        IllegalStateException e = assertThrows(IllegalStateException.class, reg::load);
        assertTrue(e.getMessage().contains("클래스패스에 없습니다"), e.getMessage());
    }

    // ── 도우미 ─────────────────────────────────────────────────────────────

    private static CollectionSiteRegistry loaded(String resource) {
        CollectionSiteRegistry reg = new CollectionSiteRegistry(resource);
        reg.load();
        return reg;
    }

    /** 지점 목록만 갈아 끼운 임시 리소스를 만들어 로드한다. */
    private static CollectionSiteRegistry withSites(String sitesJson) throws IOException {
        return loaded(writeTempResource(sitesJson));
    }

    private static void assertStartupFails(String sitesJson, String expectedFragment) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> withSites(sitesJson),
                "이 지점은 기동을 막아야 합니다.");
        assertTrue(e.getMessage().contains(expectedFragment),
                "메시지에 '" + expectedFragment + "'가 있어야 합니다. 실제: " + e.getMessage());
    }

    /**
     * 테스트 클래스 출력 디렉터리(= 클래스패스)에 임시 JSON을 써 두고 그 경로를 돌려준다.
     * 레지스트리가 클래스패스에서 읽으므로 임시 파일도 같은 방식으로 놓아야 한다.
     */
    private static String writeTempResource(String sitesJson) throws IOException {
        Path classpathRoot;
        try {
            classpathRoot = Path.of(CollectionSiteRegistryTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IOException("클래스패스 루트를 찾을 수 없습니다.", e);
        }
        Path dir = classpathRoot.resolve("collection");
        Files.createDirectories(dir);
        String name = "test-sites-" + Integer.toHexString(sitesJson.hashCode()) + ".json";
        String body = """
                {
                  "id": "test",
                  "snapThresholdMeters": 300,
                  "sites": {
                %s
                  }
                }
                """.formatted(sitesJson);
        Files.writeString(dir.resolve(name), body, StandardCharsets.UTF_8);
        return "/collection/" + name;
    }

    /** 임시 리소스가 실제로 클래스패스에서 읽히는지 — 도우미 자체가 거짓 통과를 만들지 않게 확인한다. */
    @Test
    void tempResourceHelperIsActuallyOnTheClasspath() throws Exception {
        String path = writeTempResource("");
        try (InputStream in = CollectionSiteRegistryTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "임시 리소스를 클래스패스에서 읽지 못하면 위 테스트들이 전부 무의미합니다.");
        }
    }
}
