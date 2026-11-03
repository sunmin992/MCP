"""Score gold rule matching separately from actual model extraction and end-to-end selection."""
import json
from pathlib import Path
from demo_requirement_flow import tool

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/reports/model-evaluation-2026-11-03'


def candidates(reply):
    if reply.get('toolError'):
        return []
    return sorted(m['serverId'] for m in reply.get('matches',[])
                  if not m.get('fictional') and not m.get('mismatches') and not m.get('ambiguities'))


def flat(value,prefix=''):
    if isinstance(value,dict):
        return {k:v for key,val in value.items() for k,v in flat(val,f'{prefix}.{key}' if prefix else key).items()}
    if isinstance(value,list):
        value=sorted(value)
    return {prefix:value}


def model_metrics(gold,predictions,broker):
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
        expected=flat(gold[pred['id']]['expected'])
        actual=flat(pred['profile'])
        correct=sum(k in actual and actual[k]==v for k,v in expected.items())
        reply=broker(pred['profile'])
        chosen=candidates(reply)
        expected_candidates=sorted(gold[pred['id']]['expectedCandidates'])
        rows.append(dict(id=pred['id'],run=pred['run'],exactProfile=expected==actual,
            expectedFields=len(expected),predictedFields=len(actual),correctFields=correct,
            inventedFields=len(actual.keys()-expected.keys()),candidates=chosen,
            exactCandidates=chosen==expected_candidates,falseEligibleCandidates=sorted(set(chosen)-set(expected_candidates))))
    if not rows:
        return {'status':'NOT_MEASURED','successfulModelRuns':0,'extractionExactRate':None,
                'candidateExactRate':None,'reason':'No successful model predictions; API failures are not extraction failures'},[]
    summary={'status':'MEASURED','successfulModelRuns':len(rows),
             'extractionExactRate':sum(r['exactProfile'] for r in rows)/len(rows),
             'candidateExactRate':sum(r['exactCandidates'] for r in rows)/len(rows),
             'falseEligibleCandidateCount':sum(len(r['falseEligibleCandidates']) for r in rows),
             'correctFields':sum(r['correctFields'] for r in rows),
             'expectedFields':sum(r['expectedFields'] for r in rows),
             'inventedFields':sum(r['inventedFields'] for r in rows),
             'missingCaseIds':sorted(expected_ids-{r['id'] for r in rows}),
             'actualModels':sorted({p.get('model','unknown') for p in predictions if p.get('status')=='OK'})}
    summary['fieldRecall']=summary['correctFields']/summary['expectedFields'] if summary['expectedFields'] else None
    by_case={case:[] for case in expected_ids}
    for pred in predictions:
        if pred.get('status')=='OK':
            by_case[pred['id']].append(json.dumps(pred['profile'],sort_keys=True,ensure_ascii=False))
    summary['unstableCaseIds']=[k for k,v in by_case.items() if len(set(v))>1]
    return summary,rows


def main():
    cases=[json.loads(line) for line in (OUT/'extraction-gold.jsonl').read_text(encoding='utf-8').splitlines() if line.strip()]
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
