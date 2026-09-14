#!/usr/bin/env python3
"""장량동 서브태스크 템플릿 기반 LLM 벤치마크.

오탐률 대신 사용자 요청의 파라미터 추출, 제약 위반 재질문, 검증된 값만을
사용한 시나리오 초안 생성을 측정한다. 프롬프트 스키마는 최신 템플릿에서 만든다.
"""
from __future__ import annotations
import json, math, os, re, sys, time, urllib.error, urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent
TEMPLATE_PATH = ROOT / "src/main/resources/subtask/jangnyang-simulator-v4.json"
REPORT, DETAIL = ROOT / "benchmark_report.md", ROOT / "benchmark_detail.log"
OLLAMA_URL = os.getenv("OLLAMA_URL", "http://localhost:11434/v1/chat/completions")
OPENAI_URL, OPENAI_KEY = os.getenv("OPENAI_API_URL", "https://api.openai.com/v1/chat/completions"), os.getenv("OPENAI_API_KEY", "")
RUNS, TIMEOUT = int(os.getenv("BENCHMARK_RUNS", "3")), int(os.getenv("BENCHMARK_TIMEOUT", "240"))
EXCLUDED = {x.strip() for x in os.getenv("EXCLUDE_MODELS", "").split(",") if x.strip()}

CASES = [
 {"id":"scale","request":"장량동 26개 동에 건물마다 30명씩, 7일 동안 시드 10회로 돌려줘.","expected":{"numBuildings":26,"residentsPerBuilding":30,"days":7,"seeds":10},"why":"인접한 숫자의 필드 배치"},
 {"id":"normalize","request":"장량동 원룸촌을 한 달치 돌리고 수거는 아침 여덟시 반, 민원 기준은 80%로 해줘.","expected":{"days":30,"collectionTime":"08:30","threshold":0.8},"why":"기간·시각·퍼센트 정규화"},
 {"id":"waste","request":"하루 평균 배출량 0.9kg, 배출량 표준편차 비율 0.3, 외출 시각 편차 25분으로 실험해줘.","expected":{"wasteMeanKg":0.9,"wasteSigma":0.3,"leaveSigma":25},"why":"유사 수치 의미 구분"},
 {"id":"collection","request":"POHANG_ACTUAL 배출 모델로 20:00~06:00에 배출하고 수거는 08:30과 17:30 두 번 해줘.","expected":{"dischargeTimeMode":"POHANG_ACTUAL","dischargeWindow":"20:00~06:00","collectionTimes":["08:30","17:30"]},"why":"자정 횡단 범위와 시각 목록"},
 {"id":"route","request":"truck-route 유형에서 2.5톤 트럭 2대로 Node_A, Node_C, Node_B 순서로 방문해줘.","expected":{"scenarioType":"truck-route","truckType":"MEDIUM_2P5T","truckCount":2,"routeSequence":["Node_A","Node_C","Node_B"]},"why":"차량과 방문 순서 연결"},
 {"id":"load","request":"경로 가용 적재량 1800kg, 초기 적재량 200kg, 배차 간격 45분으로 multi-truck 실험을 해줘.","expected":{"routeAvailableCapacityKg":1800,"initialTruckLoadKg":200,"dispatchIntervalMinutes":45,"scenarioType":"multi-truck"},"why":"차량 용량 파라미터 보존"},
 {"id":"traffic","request":"ZONE_PROXY_HYBRID 방식으로 교통을 반영하고 jangryang-weekday 프로파일, 같은 구역 이동은 8분, 구역 배정은 ROUND_ROBIN으로 해줘.","expected":{"travelTimeMode":"ZONE_PROXY_HYBRID","trafficMode":"APPLY","trafficProfileId":"jangryang-weekday","intraZoneTravelMinutes":8,"zoneAssignmentRule":"ROUND_ROBIN"},"why":"교통 조건 묶음"},
 {"id":"approval","request":"빈 설정에는 기본값을 모두 적용해도 돼. 엔진은 java로 single-run 실행해줘.","expected":{"defaultApproval":"ALL","engine":"java","scenarioType":"single-run"},"why":"기본값 동의와 값 창작 구분"},
 {"id":"bad-count","request":"장량동 건물 27개로 30일 시뮬레이션해줘.","expected":{"days":30},"invalid":{"numBuildings":27},"reask":["numBuildings"],"why":"범위 초과 재질문"},
 {"id":"bad-time","request":"수거 시각은 25:00, 수거장 용량은 40kg으로 설정해줘.","expected":{"capacity":40},"invalid":{"collectionTime":"25:00"},"reask":["collectionTime"],"why":"잘못된 시각 재질문"},
 {"id":"bad-enum","request":"엔진은 rust로 하고 직업 구성은 UNIVERSITY로 돌려줘.","expected":{"occupationPreset":"UNIVERSITY"},"invalid":{"engine":"rust"},"reask":["engine"],"why":"미지원 열거값 재질문"},
 {"id":"no-invention","request":"장량동 쓰레기 수거 시뮬레이터를 내 조건에 맞게 만들어줘.","expected":{},"forbidden_all":True,"why":"값 없는 요청에서 창작 금지"},
]

def models():
    names=[x.strip() for x in os.getenv("BENCHMARK_MODELS","llama3.2:3b,qwen2.5:7b,gemma:2b,gemma2:9b").split(",") if x.strip()]
    result=[{"name":n,"url":OLLAMA_URL,"key":"ollama"} for n in names]
    if OPENAI_KEY: result.append({"name":"gpt-4o-mini","url":OPENAI_URL,"key":OPENAI_KEY})
    return [m for m in result if m["name"] not in EXCLUDED]

def load_template():
    t=json.loads(TEMPLATE_PATH.read_text(encoding="utf-8"))
    f={s["answerField"]:s for s in t["subtasks"] if s["stage"]=="COLLECT"}
    return t,f

def prompt_from(t, fields):
    """지시문을 만든다. <b>배치가 결과를 좌우한다.</b>

    모델은 읽은 순서대로 다음 글자를 예측한다. 예전에는 출력 계약이 5% 지점에 한 줄로
    있고 그 뒤에 템플릿 32행(9,700자, 행 평균 304자)이 따라왔다 — 생성 직전에 읽은 것이
    `- numBuildings | type=... | rule=...` 꼴이라, 로컬 모델들이 그 모양을 베껴 평평한
    설정 객체를 냈다(계약 통과율 0%).

    그래서 셋을 지킨다:
      1. 출력 계약은 <b>맨 뒤</b>에 둔다.
      2. field 이름은 압축 목록으로 <b>따로</b> 나열한다. 304자 행 안의 한 토큰으로 두면
         모델이 buildingCount·peoplePerBuilding·seedCount처럼 이름을 지어낸다. 다만
         <b>금지문이 아니라 안내문</b>으로 쓴다 — "목록에 없는 이름을 만들면 안 된다"로
         적었더니 gpt-4o-mini가 필드 선택을 어휘 매칭으로 처리해 "기본값을 모두 적용해도
         돼"→defaultApproval=ALL 같은 정당한 추론을 포기했다.
      3. 템플릿 행(범위·검증규칙)은 그 사이에 둔다. 행 모양을 베끼는 문제는 1번 배치로
         막고, <b>"이 행을 베끼지 마라"고 부인하지는 않는다</b> — 그렇게 적었더니
         gpt-4o-mini가 그 행에서 읽어야 할 형식 정보까지 버려 dischargeWindow를
         "20:00~06:00" 대신 "1200~360"(분)으로 냈다.

    실측(같은 요청, 같은 모델): 계약을 뒤로 옮기기만 해서 gemma2:9b가 {} → 계약 통과,
    qwen2.5:7b가 값 0/4 → 3/4. 이름 목록까지 붙이면 창작 필드명이 0개가 됐다.
    """
    rows=[]
    for name,s in fields.items():
        rows.append(f'- {name} | type={s["answerType"]} | allowsNA={str(s["allowsNotApplicable"]).lower()} | allowedRange={json.dumps(s["allowedRange"],ensure_ascii=False,separators=(",",":"))} | rule={s["validationRule"]} | retry={s["retryQuestion"]}')
    return f'''당신은 포항 장량동 생활쓰레기 시뮬레이터의 요청 해석기다.
아래 서브태스크 템플릿만 설계도로 사용해 사용자 요청을 구조화하라.

규칙:
1. 요청에 실제로 명시된 값만 values에 넣고 기본값을 만들지 않는다.
2. field는 아래 이름 목록에 있는 것만 쓴다. span은 근거 원문 그대로이며 최소 두 글자다.
3. 한 달치→30, 80%→0.8, 아침 여덟시 반→08:30처럼 타입에 맞게 정규화할 수 있다.
4. 타입·범위·열거값을 위반한 값도 values에 두되 valid=false로 하고 reaskFields에 넣는다.
5. invalid 값은 기본값으로 바꾸거나 scenario에 넣지 않는다.
6. 요청에 없는 필드는 reaskFields에 넣지 않는다.
7. scenario에는 valid=true인 values만 같은 field:value로 복사하며 새 값을 추가하지 않는다.
8. targetRegion, targetDomain, requestedConclusion도 사용자 문장에서만 옮긴다.

잘못된 값 정책: {json.dumps(t.get("invalidValuePolicy",{}),ensure_ascii=False)}

템플릿 {t["subtaskSetId"]} v{t["version"]} — 값의 타입·허용범위·검증규칙이다:
{chr(10).join(rows)}

field 이름은 이 목록에서 고른다:
{", ".join(fields)}

출력은 JSON 객체 하나만 낸다. 위 이름들을 최상위 키로 쓰지 않고 반드시 이 형태를 지킨다:
{{"values":[{{"field":"...","value":값,"span":"원문","valid":true}}],"reaskFields":["..."],"scenario":{{"...":값}},"targetRegion":"","targetDomain":"","requestedConclusion":""}}'''

def call_error_text(e):
    """오류 사유를 남긴다. HTTPError의 본문에 서버가 준 이유가 들어 있으므로 버리지 않는다.

    str(e)는 "HTTP Error 400: Bad Request"뿐이다. 실제 사유("messages must contain
    the word json")는 응답 본문에만 있어서, 그것을 버리면 36번 실패해도 왜인지 알 수 없다.
    """
    body=""
    if isinstance(e,urllib.error.HTTPError):
        try: body=" | "+e.read().decode("utf-8","replace").strip()[:400]
        except Exception: pass
    return f"{type(e).__name__}: {e}{body}"

def call(m, prompt, text):
    body=json.dumps({"model":m["name"],"max_tokens":1800,"temperature":0,"response_format":{"type":"json_object"},"messages":[{"role":"system","content":prompt},{"role":"user","content":text}]},ensure_ascii=False).encode()
    req=urllib.request.Request(m["url"],data=body,headers={"Content-Type":"application/json","Authorization":"Bearer "+m["key"]})
    start=time.perf_counter()
    with urllib.request.urlopen(req,timeout=TIMEOUT) as r: data=json.loads(r.read().decode())
    return data["choices"][0]["message"]["content"],time.perf_counter()-start

def parse(raw):
    x=raw.strip(); x=re.sub(r"^```(?:json)?\s*|\s*```$","",x); x=re.sub(r",(\s*[}\]])",r"\1",x)
    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"중복 JSON 키: {key}")
            result[key] = value
        return result
    def reject_constant(value):
        raise ValueError(f"비표준 JSON 숫자: {value}")
    obj=json.loads(x, object_pairs_hook=unique_pairs, parse_constant=reject_constant)
    return obj

def contract_errors(out):
    """누락을 빈 값으로 간주하지 않는다. 계약 밖의 키도 오류로 기록한다."""
    required = {"values", "reaskFields", "scenario", "targetRegion", "targetDomain", "requestedConclusion"}
    if not isinstance(out, dict):
        return ["최상위 객체 필요"]
    errors = [f"누락: {k}" for k in sorted(required - out.keys())]
    errors += [f"계약 밖 키: {k}" for k in sorted(out.keys() - required)]
    for key in ("targetRegion", "targetDomain", "requestedConclusion"):
        if key in out and not isinstance(out[key], str):
            errors.append(f"{key}: 문자열 필요")
    if not isinstance(out.get("scenario"), dict):
        errors.append("scenario: 객체 필요")
    questions = out.get("reaskFields")
    if not isinstance(questions, list) or any(not isinstance(x, str) for x in questions):
        errors.append("reaskFields: 문자열 배열 필요")
    elif len(set(questions)) != len(questions):
        errors.append("reaskFields: 중복 필드")
    rows = out.get("values")
    if not isinstance(rows, list):
        errors.append("values: 배열 필요")
    else:
        seen = set()
        for i, row in enumerate(rows):
            if not isinstance(row, dict) or set(row) != {"field", "value", "span", "valid"}:
                errors.append(f"values[{i}]: field/value/span/valid 필수, 추가 키 금지")
                continue
            if not isinstance(row["field"], str) or not isinstance(row["span"], str) or type(row["valid"]) is not bool:
                errors.append(f"values[{i}]: field/span 문자열, valid 불리언 필요")
                continue
            if row["field"] in seen:
                errors.append(f"values[{i}]: 중복 필드 {row['field']}")
            seen.add(row["field"])
    return errors

def time_ok(v):
    if isinstance(v,bool): return False
    if isinstance(v,int): return 0<=v<=1439
    m=re.fullmatch(r"(\d{1,2}):(\d{2})",str(v).strip())
    return bool(m and int(m.group(1))<24 and int(m.group(2))<60)

def template_valid(s,v):
    k,a=s["answerType"],s["allowedRange"]
    if v is None:return s["allowsNotApplicable"]
    if k=="INTEGER": return not isinstance(v,bool) and isinstance(v,(int,float)) and math.isfinite(v) and int(v)==v and a.get("min",-math.inf)<=v<=a.get("max",math.inf)
    if k=="NUMBER": return not isinstance(v,bool) and isinstance(v,(int,float)) and math.isfinite(v) and a.get("min",-math.inf)<=v<=a.get("max",math.inf)
    if k=="ENUM": return v in a.get("values",[])
    if k=="STRING": return isinstance(v,str) and a.get("minLength",0)<=len(v)<=a.get("maxLength",math.inf)
    if k=="TIME": return time_ok(v)
    if k=="TIME_RANGE":
        p=v if isinstance(v,list) else re.split(r"\s*[~～-]\s*",v) if isinstance(v,str) else []
        return len(p)==2 and all(time_ok(x) for x in p) and p[0]!=p[1]
    if k=="TIME_LIST": return isinstance(v,list) and a.get("minItems",0)<=len(v)<=a.get("maxItems",math.inf) and all(time_ok(x) for x in v)
    if k=="STRING_LIST": return isinstance(v,list) and all(isinstance(x,str) for x in v) and a.get("minItems",0)<=len(v)<=a.get("maxItems",math.inf) and len(set(v))==len(v) and all(re.fullmatch(r"Node_[A-Z]",x) for x in v)
    return False

def same(a,e):
    if isinstance(e,(int,float)) and not isinstance(e,bool):
        try:return not isinstance(a,bool) and isinstance(a,(int,float)) and math.isclose(a,e,rel_tol=0,abs_tol=1e-9)
        except:return False
    if isinstance(e,list):return isinstance(a,list) and len(a)==len(e) and all(same(x,y) for x,y in zip(a,e))
    return type(a) is type(e) and a == e

def span_ok(req,span):
    norm=lambda x:re.sub(r"\s+","",str(x or "")).lower()
    return len(norm(span))>=2 and norm(span) in norm(req)

def check_cases(fields):
    for c in CASES:
        used=set(c.get("expected",{}))|set(c.get("invalid",{}))|set(c.get("reask",[]))
        if used-set(fields):raise ValueError(f'{c["id"]}: 템플릿에 없는 필드 {used-set(fields)}')
        for f,v in c.get("expected",{}).items():
            if not template_valid(fields[f],v):raise ValueError(f'{c["id"]}: 유효 기대값 오류 {f}={v}')
        for f,v in c.get("invalid",{}).items():
            if template_valid(fields[f],v):raise ValueError(f'{c["id"]}: invalid 라벨 오류 {f}={v}')

def score(c,out,fields):
    errors = contract_errors(out)
    if errors:
        return {"pass": False, "contract": False, "contractErrors": errors,
                "recalled": 0, "stated": len(set(c.get("expected", {})) | set(c.get("invalid", {}))),
                "values": 0, "spans": 0, "constraints": 0, "reask": False,
                "scenario": False, "noInvention": False}
    rows=out.get("values",[]) if isinstance(out.get("values"),list) else []
    found,dup,unknown={},set(),set()
    for row in rows:
        if not isinstance(row,dict) or not isinstance(row.get("field"),str):continue
        f=row["field"]
        if f not in fields:unknown.add(f)
        elif f in found:dup.add(f)
        else:found[f]=row
    expected,invalid=c.get("expected",{}),c.get("invalid",{}); all_labeled={**expected,**invalid}; stated=set(all_labeled)
    recalled=sum(f in found for f in stated); values=sum(f in found and same(found[f].get("value"),v) for f,v in all_labeled.items())
    spans=sum(f in found and span_ok(c["request"],found[f].get("span")) for f in stated)
    constraints=sum(f in found and same(found[f]["value"],all_labeled[f]) and found[f]["valid"] == (f not in invalid) and found[f]["valid"]==template_valid(fields[f],found[f]["value"]) for f in stated)
    invented=set(found)-stated; reask=set(x for x in out.get("reaskFields",[]) if isinstance(x,str)); reask_exact=reask==set(c.get("reask",[]))
    scenario=out.get("scenario",{}) if isinstance(out.get("scenario"),dict) else {}
    scenario_ok=all(f in scenario and same(scenario[f],v) and f in found and found[f]["valid"] and same(found[f]["value"],v) and span_ok(c["request"],found[f]["span"]) and template_valid(fields[f],v) for f,v in expected.items()) and all(f not in scenario for f in invalid) and not(set(scenario)-set(expected))
    passed=recalled==len(stated) and values==len(stated) and spans==len(stated) and constraints==len(stated) and not invented and not unknown and not dup and reask_exact and scenario_ok
    if c.get("forbidden_all"):passed=passed and not found and not scenario
    return {"pass":passed,"contract":True,"contractErrors":[],"recalled":recalled,"stated":len(stated),"values":values,"spans":spans,"constraints":constraints,"reask":reask_exact,"scenario":scenario_ok,"noInvention":bool(c.get("forbidden_all") and not rows and not scenario),"invented":sorted(invented),"unknown":sorted(unknown),"duplicate":sorted(dup)}

def run(m,prompt,fields,detail):
    total={k:0 for k in ["runs","responses","callErrors","format","contract","pass","recalled","stated","values","spans","constraints","reask","scenario","noInvention","noInventionCases"]}; total["latency"]=0.0
    for c in CASES:
        for n in range(1,RUNS+1):
            total["runs"]+=1
            try:
                raw,dt=call(m,prompt,c["request"])
            except (urllib.error.URLError,TimeoutError,OSError) as e:
                total["callErrors"] += 1
                detail.append(f'[{m["name"]}] {c["id"]} run={n}\n호출 오류: {call_error_text(e)}\n')
                continue
            total["responses"] += 1
            total["latency"] += dt
            total["stated"] += len(set(c.get("expected", {})) | set(c.get("invalid", {})))
            total["noInventionCases"] += int(bool(c.get("forbidden_all")))
            try:
                out=parse(raw)
            except (ValueError,TypeError) as e:
                detail.append(f'[{m["name"]}] {c["id"]} run={n}\n출력 파싱 오류: {e}\n원문: {raw}\n')
                continue
            total["format"] += 1
            s=score(c,out,fields)
            for k in ["contract","pass","recalled","values","spans","constraints","reask","scenario","noInvention"]:
                total[k]+=int(s[k])
            if not s["pass"]:detail.append(f'[{m["name"]}] {c["id"]} run={n}\n요청: {c["request"]}\n채점: {json.dumps(s,ensure_ascii=False)}\n원문: {raw}\n')
    return total

def pct(n,d):return "-" if not d else f"{100*n/d:.1f}%"

def report(t,results,skipped):
    lines=["# 장량동 서브태스크 템플릿 기반 LLM 벤치마크","",f'- 템플릿: `{t["subtaskSetId"]}` v{t["version"]}',f'- invalid 정책: `{json.dumps(t.get("invalidValuePolicy",{}),ensure_ascii=False)}`',f"- 케이스: {len(CASES)}개 × {RUNS}회","- 대상: 명시값 추출 → span 검증 → 제약 위반 재질문 → 시나리오 값 보존","","| 모델 | 완전 통과 | JSON | 필드 재현 | 값 정확 | span | 제약판정 | 재질문 | 시나리오 | 지어낸 값 없음 | 평균 지연 |","|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    lines[-2:] = ["| 모델 | 응답/시도 | 호출 오류 | 완전 통과 | JSON | 출력 계약 | 필드 재현 | 값 정확 | span | 제약판정 | 재질문 | 시나리오 | 지어낸 값 없음 | 평균 지연 |",
                  "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for name,r in results.items():
        count = r["responses"]
        latency = f'{r["latency"]/count:.2f}s' if count else "-"
        lines.append(f'| {name} | {count}/{r["runs"]} | {r["callErrors"]} | {pct(r["pass"],count)} | {pct(r["format"],count)} | {pct(r["contract"],count)} | {pct(r["recalled"],r["stated"])} | {pct(r["values"],r["stated"])} | {pct(r["spans"],r["stated"])} | {pct(r["constraints"],r["stated"])} | {pct(r["reask"],count)} | {pct(r["scenario"],count)} | {pct(r["noInvention"],r["noInventionCases"])} | {latency} |')
    lines += ["", "채점 버전 2: 호출 오류는 모델 점수에서 제외하고 별도 집계한다. 응답이 없으면 점수와 지연은 '-'(미측정)이다.",
              "응답 기준 지표에는 JSON/계약 실패도 실패로 포함한다. 필드 지표의 분모는 응답을 받은 요청의 전체 기대 필드 수다.",
              "필수 구조 누락·타입 오류·추가 키는 계약 실패이며 재질문·시나리오·지어낸 값 없음을 통과시키지 않는다."]
    if skipped:lines += ["","## 실행하지 못한 모델",""]+[f"- `{n}`: {why}" for n,why in skipped.items()]
    lines += ["","`완전 통과`는 모든 명시값의 필드·값·span이 맞고, invalid 필드만 재질문하며, 유효한 값만 시나리오에 보존한 실행이다.","","`지어낸 값 없음`은 요청에 값이 하나도 없을 때 모델이 값을 지어내지 않은 비율이다. 다른 열과 같이 높을수록 좋다. 되묻기 여부는 세지 않는다 — 그것은 `재질문` 열이 따로 재고, 겹쳐 재면 값을 하나도 지어내지 않은 모델과 전 필드를 지어낸 모델이 같은 숫자가 된다(실측으로 겪었다).","","실패별 모델 원문은 `benchmark_detail.log`에 기록된다."]
    return "\n".join(lines)+"\n"

def main():
    t,fields=load_template(); check_cases(fields); prompt=prompt_from(t,fields); detail=[]; results={}; skipped={}
    for m in models():
        print(f'[{m["name"]}] {len(CASES)} cases × {RUNS}',flush=True)
        try:results[m["name"]]=run(m,prompt,fields,detail)
        except (urllib.error.URLError,TimeoutError,OSError) as e:skipped[m["name"]]=f"연결 실패: {e}"
    REPORT.write_text(report(t,results,skipped),encoding="utf-8"); DETAIL.write_text("\n".join(detail) or "모든 케이스 통과\n",encoding="utf-8")
    print(f"리포트 저장: {REPORT}"); return 0 if any(r["responses"] for r in results.values()) else 2

if __name__=="__main__":
    if "--legacy" in sys.argv:
        sys.argv.remove("--legacy")
        sys.exit(main())
    from extraction_benchmark import main as extraction_main
    extraction_main()
