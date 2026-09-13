package com.wastesim.registry;

import com.wastesim.model.SimulationConfig;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

/**
 * 실행 설정이 실제로 받는 입력 필드. <b>계약이 아니라 코드에서 읽는다.</b>
 *
 * <p><b>왜 리플렉션인가</b>: 목록을 손으로 적으면 그 목록도 계약이 되고, 계약이 누락하면
 * 검증기도 같이 누락한다는 바로 그 문제를 한 겹 더 만든다. 세터를 직접 세면 코드가 바뀔
 * 때 목록도 함께 바뀐다 — 이 검사만은 사람의 선언을 거치지 않아야 의미가 있다.
 *
 * <p>{@code SimulationConfig}를 읽기만 하고 수정하지 않는다.
 */
public final class SimulationConfigFields {

    private SimulationConfigFields() { }

    /** 세터 이름에서 얻은 필드명 집합. {@code setDays} → {@code days}. */
    public static Set<String> all() {
        Set<String> fields = new TreeSet<>();
        for (Method m : SimulationConfig.class.getMethods()) {
            String name = m.getName();
            if (!name.startsWith("set") || name.length() <= 3) continue;
            if (m.getParameterCount() != 1) continue;
            fields.add(Character.toLowerCase(name.charAt(3)) + name.substring(4));
        }
        return fields;
    }
}
