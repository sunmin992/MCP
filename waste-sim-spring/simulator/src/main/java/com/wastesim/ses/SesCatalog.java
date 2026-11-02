package com.wastesim.ses;

import com.wastesim.model.SimulationConfig;
import com.wastesim.template.TemplateCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * SES 원본 트리를 띄울 때 한 번 읽고, 템플릿과 어긋나면 뜨지 않게 한다.
 */
@Component
public class SesCatalog {

    private final SesTree tree;

    @Autowired
    public SesCatalog(TemplateCatalog templates) {
        this(templates, SesTree.DEFAULT_RESOURCE);
    }

    SesCatalog(TemplateCatalog templates, String resource) {
        SesTree t = SesTree.load(resource);
        if (!t.sesId().equals(templates.sesId()) || !t.sesVersion().equals(templates.sesVersion())) {
            throw new IllegalStateException("SES 원본(" + t.sesId() + " " + t.sesVersion()
                    + ")이 템플릿(" + templates.sesId() + " " + templates.sesVersion() + ")과 다릅니다");
        }
        for (SesTree.Decision d : t.decisions()) {
            // configField 는 필수 칸이다(spec §6-3). 없거나 비면 조용히 넘기지 않는다.
            String field = d.fields().path("configField").asText("");
            if (field.isEmpty()) {
                throw new IllegalStateException("configField 가 없거나 빈 결정입니다: "
                        + d.fields().path("templateId").asText("?") + " (" + d.sesPath() + ")");
            }
            if (!hasGetter(field)) {
                throw new IllegalStateException("SimulationConfig 에 없는 configField 입니다: "
                        + field + " (" + d.sesPath() + ")");
            }
        }
        this.tree = t;
    }

    public SesTree tree() {
        return tree;
    }

    private static boolean hasGetter(String field) {
        String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        for (Method m : SimulationConfig.class.getMethods()) {
            if (m.getParameterCount() == 0 && (m.getName().equals("get" + cap) || m.getName().equals("is" + cap))) {
                return true;
            }
        }
        return false;
    }
}
