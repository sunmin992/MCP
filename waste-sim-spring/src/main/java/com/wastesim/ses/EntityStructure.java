package com.wastesim.ses;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SES 트리 한 벌. 이름으로 마디를 찾고, 루트에서 그 마디까지의 경로를 준다. */
public final class EntityStructure {

    private final String root;
    private final Map<String, SesEntity> entities;
    private final List<Coupling> couplings;

    public EntityStructure(String root, Map<String, SesEntity> entities, List<Coupling> couplings) {
        this.root = root;
        this.entities = new LinkedHashMap<>(entities);
        this.couplings = List.copyOf(couplings);
    }

    public String root() {
        return root;
    }

    public SesEntity entity(String name) {
        SesEntity e = entities.get(name);
        if (e == null) throw new IllegalArgumentException("SES에 없는 엔티티: " + name);
        return e;
    }

    /** 선언 순서를 유지한다 — 문항 순서가 매번 같아야 한다. */
    public Collection<SesEntity> entities() {
        return entities.values();
    }

    public List<Coupling> couplings() {
        return couplings;
    }

    /**
     * 루트에서 그 엔티티까지의 이름 경로. 문항이 어느 화면 단계에 속하는지를 이 경로의
     * 두 번째 마디로 정한다.
     */
    public List<String> pathTo(String entityName) {
        List<String> path = new ArrayList<>();
        if (search(root, entityName, path)) return List.copyOf(path);
        throw new IllegalArgumentException("루트에서 닿지 않는 엔티티: " + entityName);
    }

    private boolean search(String current, String target, List<String> path) {
        path.add(current);
        if (current.equals(target)) return true;
        SesEntity e = entities.get(current);
        if (e != null) {
            for (Decomposition d : e.decompositions()) {
                for (String child : d.children()) {
                    if (search(child, target, path)) return true;
                }
            }
        }
        path.remove(path.size() - 1);
        return false;
    }
}
