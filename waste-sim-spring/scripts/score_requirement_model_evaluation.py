"""Score gold rule matching separately from actual model extraction and end-to-end selection."""
import json
import sys
from pathlib import Path
from demo_requirement_flow import tool

ROOT=Path(__file__).resolve().parents[1]
GOLD=ROOT/'docs/reports/model-evaluation-2026-11-03/extraction-gold.jsonl'
# 다른 모델(예: Ollama) 결과 폴더를 인자로 받는다. 기본은 gpt-4.1-mini 평가 폴더.
OUT=Path(sys.argv[1]) if len(sys.argv)>1 else GOLD.parent


def candidates(reply):
    if reply.get('toolError'):
        return []
    return sorted(m['serverId'] for m in reply.get('matches',[])
                  if not m.get('fictional') and not m.get('mismatches') and not m.get('ambiguities'))


import re


def squash(text):
    return re.sub(r'[\s\W_]+', '', str(text)).lower()


def same_phrase(a, b):
    """사용자 표현은 조금 다르게 옮겨도("평균" 생략, 조사 붙음) 한쪽이 다른 쪽을 품으면 같은 항목으로 본다."""
    a, b = squash(a), squash(b)
    return bool(a) and bool(b) and (a in b or b in a)


def items(profile):
    """채점 단위. 근거 구절(evidence)과 스키마 버전은 빼고, 표현 목록은 순서와 무관하게 본다."""
    req = profile.get('requirements', {})
    out = {'domain': profile.get('domain'), 'spatialScale': profile.get('spatialScale')}
    for k in ['regionId', 'simulationDuration', 'timeResolution']:
        out[k] = req.get(k)
    out['requiredOutputs'] = sorted(req.get('requiredOutputs') or [])
    for k in ['constraints', 'preferences']:
        v = req.get(k) or []
        if isinstance(v, dict):  # 옛 형식 {필드: 값}
            v = [{'name': f, 'value': x} for f, x in v.items()]
        out[k] = sorted(({'name': c['name'], 'value': c['value'], 'unit': c.get('unit')} for c in v),
                        key=lambda c: (str(c['name']), c['value']))
    return {k: v for k, v in out.items() if v not in (None, [])}


def item_equal(key, want, got):
    if key in ('regionId',):
        return same_phrase(want, got)
    if key == 'requiredOutputs':
        return len(want) == len(got) and all(any(same_phrase(w, g) for g in got) for w in want)
    if key in ('constraints', 'preferences'):
        return len(want) == len(got) and all(any(same_phrase(w['name'], g['name']) and w['value'] == g['value']
                                                 and (w.get('unit') or '') == (g.get('unit') or '') for g in got) for w in want)
    return want == got


def compare(expected, actual):
    e, a = items(expected), items(actual)
    correct = [k for k, v in e.items() if k in a and item_equal(k, v, a[k])]
    return dict(exact=len(correct) == len(e) and a.keys() == e.keys(), expectedFields=len(e), predictedFields=len(a),
                correctFields=len(correct), inventedFields=sorted(a.keys() - e.keys()),
                wrongFields=sorted(k for k in e if k in a and k not in correct), missingFields=sorted(e.keys() - a.keys()))


def model_metrics(gold,predictions,broker):
    """①층 공통 해석 정확도와, 틀린 해석이 브로커에서 걸렸는지(근거 검사 · 후보 판정)를 함께 센다."""
    expected_ids=set(gold)
    rows=[]
    seen=set()
    for pred in predictions:
        key=(pred['id'],pred['run'])
        if key in seen or pred['id'] not in expected_ids:
            raise ValueError('Duplicate or unknown prediction')
        seen.add(key)
        if pred.get('status')!='OK':
            continue
        cmp=compare(gold[pred['id']]['expected'],pred['profile'])
        request=dict(pred['profile']); request['userUtterances']=[gold[pred['id']]['utterance']]
        reply=broker(request)
        blocked=bool(reply.get('toolError'))
        chosen=[] if blocked else candidates(reply)
        expected_candidates=sorted(gold[pred['id']]['expectedCandidates'])
        outcome=('CORRECT' if cmp['exact'] else 'WRONG_BLOCKED_BY_EVIDENCE' if blocked
                 else 'WRONG_NO_CANDIDATE' if not chosen else 'WRONG_SAME_CANDIDATES' if chosen==expected_candidates
                 else 'WRONG_REACHED_OTHER_CANDIDATES')
        rows.append(dict(id=pred['id'],run=pred['run'],exactProfile=cmp['exact'],**{k:v for k,v in cmp.items() if k!='exact'},
            evidenceStatus=None if blocked else reply.get('requirementEvidence',{}).get('status'),
            candidates=chosen,exactCandidates=chosen==expected_candidates,
            falseEligibleCandidates=sorted(set(chosen)-set(expected_candidates)),outcome=outcome))
    if not rows:
        return {'status':'NOT_MEASURED','successfulModelRuns':0,'extractionExactRate':None,
                'candidateExactRate':None,'reason':'No successful model predictions; API failures are not extraction failures'},[]
    wrong=[r for r in rows if not r['exactProfile']]
    summary={'status':'MEASURED','successfulModelRuns':len(rows),
             'extractionExactRate':sum(r['exactProfile'] for r in rows)/len(rows),
             'candidateExactRate':sum(r['exactCandidates'] for r in rows)/len(rows),
             'falseEligibleCandidateCount':sum(len(r['falseEligibleCandidates']) for r in rows),
             'correctFields':sum(r['correctFields'] for r in rows),
             'expectedFields':sum(r['expectedFields'] for r in rows),
             'inventedFields':sum(len(r['inventedFields']) for r in rows),
             'wrongInterpretations':len(wrong),
             'outcomes':{o:sum(r['outcome']==o for r in rows) for o in sorted({r['outcome'] for r in rows})},
             # 틀린 해석 가운데 다른 후보로 실행까지 이어질 수 있었던 비율. 신뢰도 = 1 - 이 값
             'wrongReachingOtherCandidatesRate':(sum(r['outcome']=='WRONG_REACHED_OTHER_CANDIDATES' for r in wrong)/len(wrong)) if wrong else 0.0,
             'missingCaseIds':sorted(expected_ids-{r['id'] for r in rows}),
             'actualModels':sorted({p.get('model','unknown') for p in predictions if p.get('status')=='OK'})}
    summary['fieldRecall']=summary['correctFields']/summary['expectedFields'] if summary['expectedFields'] else None
    by_case={case:[] for case in expected_ids}
    for pred in predictions:
        if pred.get('status')=='OK':
            by_case[pred['id']].append(json.dumps(items(pred['profile']),sort_keys=True,ensure_ascii=False))
    summary['unstableCaseIds']=[k for k,v in by_case.items() if len(set(v))>1]
    return summary,rows


def main():
    cases=[json.loads(line) for line in GOLD.read_text(encoding='utf-8').splitlines() if line.strip()]
    gold={c['id']:c for c in cases}
    oracle=[]
    for case in cases:
        reply=tool(8089,'find_simulators',case['expected'])
        actual=candidates(reply)
        oracle.append({'id':case['id'],'expectedCandidates':sorted(case['expectedCandidates']),
                       'actualCandidates':actual,'correct':actual==sorted(case['expectedCandidates']),'brokerResponse':reply})
    predictions=[]
    if (OUT/'predictions.jsonl').exists():
        predictions=[json.loads(line) for line in (OUT/'predictions.jsonl').read_text(encoding='utf-8').splitlines() if line.strip()]
    summary,rows=model_metrics(gold,predictions,lambda p:tool(8089,'find_simulators',p))
    report={'goldRuleMatching':{'cases':len(oracle),'correct':sum(r['correct'] for r in oracle),
                               'interpretation':'Gold structured inputs, not LLM extraction accuracy'},
            'modelEvaluation':summary,'oracleRuns':oracle,'modelRuns':rows}
    (OUT/'extraction-and-selection-metrics.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:report[k] for k in ['goldRuleMatching','modelEvaluation']},ensure_ascii=True))


if __name__=='__main__':
    main()
