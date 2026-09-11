package com.wastesim.ses;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EntityStructureTest {

    /** 축소판 트리 — 핵심 타입의 동작만 본다. 장량동 실물은 Task 3에서 다룬다. */
    private static EntityStructure tiny() {
        SesEntity root = new SesEntity("루트", List.of(),
                List.of(new Decomposition(Decomposition.Kind.ASPECT, "구성", List.of("차량 집합"))));
        SesEntity fleet = new SesEntity("차량 집합", List.of("개수"),
                List.of(new Decomposition(Decomposition.Kind.MULTI, "차량 다중", List.of("차량"))));
        SesEntity truck = new SesEntity("차량", List.of("적재용량"),
                List.of(new Decomposition(Decomposition.Kind.SPEC, "차종 축", List.of("5톤", "1톤"))));
        SesEntity big = new SesEntity("5톤", List.of(), List.of());
        SesEntity small = new SesEntity("1톤", List.of(), List.of());
        return new EntityStructure("루트",
                Map.of("루트", root, "차량 집합", fleet, "차량", truck, "5톤", big, "1톤", small),
                List.of());
    }

    @Test
    void specAxisIsFoundOnEntity() {
        List<Decomposition> axes = tiny().entity("차량").specAxes();
        assertEquals(1, axes.size());
        assertEquals("차종 축", axes.get(0).name());
        assertEquals(List.of("5톤", "1톤"), axes.get(0).children());
    }

    @Test
    void multiAspectIsFoundOnHolder() {
        assertTrue(tiny().entity("차량 집합").multiAspect().isPresent());
        assertTrue(tiny().entity("차량").multiAspect().isEmpty());
    }

    @Test
    void pathRunsFromRootToEntity() {
        assertEquals(List.of("루트", "차량 집합", "차량"), tiny().pathTo("차량"));
    }

    @Test
    void unknownEntityFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> tiny().entity("없는 것"));
    }
}
