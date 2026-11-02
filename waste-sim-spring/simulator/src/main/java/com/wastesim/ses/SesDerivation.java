package com.wastesim.ses;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** SES 트리 원본에서 커밋되는 파생물을 만든다. 파생물이 원본과 어긋나면 SesDerivationTest 가 잡는다. */
public final class SesDerivation {

    private SesDerivation() {
    }

    /** 템플릿 JSON. 각 템플릿 = 결정의 템플릿 칸 + sesPath + nodeKind. */
    public static ObjectNode templates(SesTree ses) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("sesId", ses.sesId());
        out.put("sesVersion", ses.sesVersion());
        ArrayNode templates = out.putArray("templates");
        for (SesTree.Decision d : ses.decisions()) {
            ObjectNode t = d.fields().deepCopy();
            t.put("sesPath", d.sesPath());
            t.put("nodeKind", d.nodeKind());
            templates.add(t);
        }
        return out;
    }

    /** 카드의 sesDigest. decisionPoint = path · kind · field + 결정의 card 칸. */
    public static ObjectNode digest(SesTree ses) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("sesId", ses.sesId());
        out.put("sesVersion", ses.sesVersion());
        ArrayNode entities = out.putArray("entities");
        ses.raw().get("entities").forEach(e -> {
            if (e.hasNonNull("label")) {
                entities.add(e.get("label").asText());
            }
        });
        ArrayNode points = out.putArray("decisionPoints");
        for (SesTree.Decision d : ses.decisions()) {
            if (d.card() == null) {
                continue;
            }
            ObjectNode p = points.addObject();
            p.put("path", d.sesPath());
            p.put("kind", d.nodeKind());
            p.set("field", d.fields().get("configField"));
            p.setAll(d.card());
        }
        return out;
    }

    /** 카드 사본에서 sesDigest 칸만 바꾼다. 키 순서는 그대로. */
    public static ObjectNode withDigest(ObjectNode card, SesTree ses) {
        ObjectNode copy = card.deepCopy();
        copy.set("sesDigest", digest(ses));
        return copy;
    }
}
