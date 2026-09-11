"""v4의 문면을 answerField를 키로 하는 v5 문면 파일로 옮긴다.

문면을 새로 쓰지 않는 이유: v5와 v4를 대조할 때 문장이 달라져 있으면 구조가
달라진 것인지 말이 달라진 것인지 구별할 수 없다.

    python -X utf8 docs/research/s1-ses-extraction/gen_prose_v5.py > src/main/resources/subtask/jangnyang-prose-v5.json

Windows에서는 반드시 -X utf8로 돌린다 — 기본 콘솔/리다이렉트 인코딩이 cp949라
한글 문면이 깨져서 저장된다.
"""
import json
import pathlib

root = pathlib.Path(__file__).resolve().parents[3]
v4 = json.loads((root / "src/main/resources/subtask/jangnyang-simulator-v4.json")
                .read_text(encoding="utf-8"))

prose = {}
for st in v4["subtasks"]:
    prose[st["answerField"]] = {
        "question": st["question"],
        "retryQuestion": st["retryQuestion"],
        "validationRule": st["validationRule"],
        "completionCondition": st["completionCondition"],
        "allowsNotApplicable": st["allowsNotApplicable"],
        "basis": st.get("basis"),
    }

print(json.dumps({"sourceSet": v4["subtaskSetId"], "entries": prose},
                 ensure_ascii=False, indent=2))
