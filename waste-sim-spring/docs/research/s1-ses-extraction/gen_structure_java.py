# docs/research/s1-ses-extraction/gen_structure_java.py
"""reference-ses.json을 JangnyangEntityStructure.java로 한 번 옮긴다.

51개를 손으로 옮겨 적으면 오타가 섞이고, 그 오타는 "정답지와 코드가 갈라졌다"는
신호와 구별되지 않는다. 최초 변환만 기계가 하고, 이후 수정은 사람이 자바 쪽에서 한다.

Windows에서 그냥 리다이렉트하면 콘솔 코드페이지(cp949)로 한글이 깨진다. 반드시
`python -X utf8 gen_structure_java.py > ...` 로 돌리거나 PYTHONUTF8=1을 설정한다.

    python -X utf8 gen_structure_java.py > ../../../src/main/java/com/wastesim/ses/JangnyangEntityStructure.java
"""
import json
import pathlib

KIND = {"aspect": "ASPECT", "spec": "SPEC", "multi": "MULTI"}

ref = json.loads((pathlib.Path(__file__).parent / "reference-ses.json").read_text(encoding="utf-8"))


def q(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


def java_list(items):
    return "List.of(" + ", ".join(items) + ")" if items else "List.of()"


lines = [
    "package com.wastesim.ses;",
    "",
    "import java.util.LinkedHashMap;",
    "import java.util.List;",
    "import java.util.Map;",
    "",
    "/**",
    " * 장량동 시뮬레이터의 SES 트리. 참조 " + ref["_meta"]["version"] + "에서 옮겨 적었고,",
    " * JangnyangStructureMatchesReferenceTest가 그 정답지와 갈라졌는지 지킨다.",
    " *",
    " * <p>이 선언이 <b>문항 세트의 출처</b>다. 여기 spec 축이 하나 늘면 물어볼 것이 하나 는다.",
    " */",
    "public final class JangnyangEntityStructure {",
    "",
    "    private static final EntityStructure INSTANCE = build();",
    "",
    "    private JangnyangEntityStructure() { }",
    "",
    "    public static EntityStructure get() {",
    "        return INSTANCE;",
    "    }",
    "",
    "    private static EntityStructure build() {",
    "        Map<String, SesEntity> e = new LinkedHashMap<>();",
]

for name, ent in ref["entities"].items():
    attrs = java_list([q(a) for a in ent.get("attrs", [])])
    decs = []
    for d in ent.get("decompositions", []):
        decs.append("new Decomposition(Decomposition.Kind.%s, %s, %s)"
                    % (KIND[d["kind"]], q(d["name"]), java_list([q(c) for c in d["children"]])))
    lines.append("        e.put(%s, new SesEntity(%s, %s, %s));"
                 % (q(name), q(name), attrs, java_list(decs)))

lines += ["", "        List<Coupling> couplings = List.of("]
cps = []
for c in ref["couplings"]:
    active = q(c["active_when"]) if c.get("active_when") else "null"
    cps.append("                new Coupling(%s, %s, %s, %s)"
               % (q(c["from"]), q(c["to"]), q(c["mechanism"]), active))
lines.append(",\n".join(cps))
lines += [
    "        );",
    "",
    "        return new EntityStructure(%s, e, couplings);" % q(ref["root"]),
    "    }",
    "}",
]

print("\n".join(lines))
