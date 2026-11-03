"""Bounded, inference-only extraction evaluation; no tools, agents, or approval calls."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
SYSTEM = """시뮬레이션 사용자 발화를 브로커 요청으로 구조화하라. JSON만 반환한다.
발화에 없는 지역, 기간, 시간 해상도, 규모, 수치, 지표를 추정하지 말고 null로 둔다.
도메인: 쓰레기/폐기물 수거=waste-collection, 공영주차=public-parking,
민원 창구=civil-service-counter, 시내버스=public-bus.
지역명은 발화 그대로 보존한다. '아무 동네'는 지역 미지정이므로 null이다.
규모는 명시적인 블록/원룸촌이면 '한 블록', 동 전체/행정동 전체이면 '행정동',
도심이라고 명시하면 '도심', 민원실 한 곳이라고 명시하면 '민원실'로 표준화한다.
지역 이름이나 도메인 이름만으로 규모를 추정하지 않는다.
simulationDuration은 전체 실행 기간, timeResolution은 결과 시간 간격이다. 서로 바꾸지 마라.
시간 단위는 second/minute/hour/day/week이고 원문 단위를 보존한다. 0일 같은 부적절한 요청도 임의 수정하지 않는다.
가능하면/좋겠어는 preferences, 그 밖의 지정 수치는 constraints로 넣는다.
반복 횟수=seeds, 블록 건물 수=numBuildings, 주차장 수=lotCount, 창구 수=counterCount.
명시한 결과 지표만 넣는다: 평균 폐기물 민원 수=meanComplaints, 평균 수거 운행 소요 시간=avgRouteDurationMinutes,
수거일당 잔여 폐기물량=judgement.leftoverKgPerCollectionDay,
평균 주차 탐색 시간=judgement.meanSearchMinutesAll, 민원 창구 평균 대기 시간=judgement.meanWaitMinutesAll.
조건이 없는 필드는 null로 반환하고 임의 기본값을 추가하지 않는다.
"""


def schema():
    time_value = {'type': ['object', 'null'], 'additionalProperties': False,
                  'properties': {'value': {'type': 'number'}, 'unit': {'type': 'string', 'enum': ['second','minute','hour','day','week']}},
                  'required': ['value','unit']}
    entries = {'type': ['array','null'], 'items': {'type': 'object', 'additionalProperties': False,
               'properties': {'field': {'type': 'string'}, 'value': {'type': 'number'}}, 'required': ['field','value']}}
    props = {'domain': {'type': 'string'}, 'spatialScale': {'type': ['string','null']},
             'regionId': {'type': ['string','null']}, 'simulationDuration': time_value, 'timeResolution': time_value,
             'requiredOutputs': {'type': ['array','null'], 'items': {'type': 'string'}},
             'constraints': entries, 'preferences': entries}
    return {'type': 'object', 'additionalProperties': False, 'properties': props, 'required': list(props)}


def profile(raw):
    out = {'domain': raw['domain'], 'requirements': {}}
    if raw['spatialScale'] is not None:
        out['spatialScale'] = raw['spatialScale']
    for k in ['regionId','simulationDuration','timeResolution','requiredOutputs']:
        if raw[k] is not None:
            out['requirements'][k] = raw[k]
    for k in ['constraints','preferences']:
        if raw[k]:
            pairs = raw[k]
            if len({p['field'] for p in pairs}) != len(pairs):
                raise ValueError('duplicate_constraint')
            out['requirements'][k] = {p['field']: p['value'] for p in pairs}
    return out


def infer(case, repeat, model, output):
    prompt = {'model': model, 'store': False, 'temperature': 0, 'max_output_tokens': 900,
              'input': [{'role':'system','content':SYSTEM}, {'role':'user','content':case['utterance']}],
              'text': {'format': {'type':'json_schema','name':'request_profile','strict':True,'schema':schema()}}}
    # Only utterance and the frozen public extraction specification reach the model. Gold labels never do.
    started = time.monotonic()
    row = {'id':case['id'], 'run':repeat, 'requestedModel':model,
           'promptVersion':'requirements-v1', 'promptSha256':hashlib.sha256(SYSTEM.encode()).hexdigest(),
           'startedAt':datetime.now(timezone.utc).isoformat()}
    try:
        request = urllib.request.Request('https://api.openai.com/v1/responses', data=json.dumps(prompt).encode(),
                    headers={'Authorization':'Bearer '+os.environ['OPENAI_API_KEY'], 'Content-Type':'application/json'})
        with urllib.request.urlopen(request,timeout=60) as response:
            result = json.load(response)
        text = ''.join(c['text'] for item in result.get('output',[]) if item.get('type')=='message'
                       for c in item.get('content',[]) if c.get('type')=='output_text')
        row.update(model=result.get('model'), responseId=result.get('id'), usage=result.get('usage'),
                   responseStatus=result.get('status'), raw=json.loads(text))
        row['profile'] = profile(row['raw'])
        row['status'] = 'OK'
    except urllib.error.HTTPError as e:
        # Do not persist provider messages: auth errors can echo fragments of credentials.
        row.update(status='API_ERROR',httpStatus=e.code)
        try:
            error=json.loads(e.read()).get('error',{})
            for key in ['code','type']:
                value=error.get(key)
                if isinstance(value,str) and value.replace('_','').isalnum() and len(value)<100:
                    row['apiError'+key.title()]=value
        except (ValueError,AttributeError):
            pass
    except Exception as e:
        row.update(status='ERROR',errorType=type(e).__name__)
    row['seconds'] = round(time.monotonic()-started,3)
    path = output / 'runs' / f"{case['id']}-r{repeat}.json"
    path.write_text(json.dumps(row,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return row


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gold',type=Path,default=ROOT/'docs/reports/model-evaluation-2026-11-03/extraction-gold.jsonl')
    parser.add_argument('--output',type=Path,default=ROOT/'docs/reports/model-evaluation-2026-11-03')
    parser.add_argument('--model',default='gpt-4.1-mini')
    parser.add_argument('--repeats',type=int,default=3)
    parser.add_argument('--limit',type=int,default=24)
    args=parser.parse_args()
    if not 1 <= args.repeats <= 3 or not 1 <= args.limit <= 24:
        raise ValueError('Bounded evaluation: at most 24 cases x 3 repeats')
    cases=[json.loads(line) for line in args.gold.read_text(encoding='utf-8').splitlines() if line.strip()][:args.limit]
    (args.output/'runs').mkdir(parents=True,exist_ok=True)
    (args.output/'extraction-system-prompt.txt').write_text(SYSTEM,encoding='utf-8')
    (args.output/'extraction-output-schema.json').write_text(json.dumps(schema(),indent=2)+'\n',encoding='utf-8')
    rows=[]
    first=infer(cases[0],1,args.model,args.output)
    rows.append(first)
    print(first['id'],first['run'],first['status'],flush=True)
    if first['status']!='OK':
        (args.output/'predictions.jsonl').write_text(json.dumps(first,ensure_ascii=False)+'\n',encoding='utf-8')
        print(json.dumps({'completed':1,'ok':0,'blocked':True,'httpStatus':first.get('httpStatus'),
                          'apiErrorCode':first.get('apiErrorCode')}))
        raise SystemExit(2)
    with ThreadPoolExecutor(max_workers=3) as executor:
        futures=[executor.submit(infer,c,r,args.model,args.output) for c in cases for r in range(1,args.repeats+1)
                 if not (c['id']==cases[0]['id'] and r==1)]
        for future in as_completed(futures):
            row=future.result(); rows.append(row)
            print(row['id'],row['run'],row['status'],flush=True)
    rows.sort(key=lambda r:(r['id'],r['run']))
    (args.output/'predictions.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows),encoding='utf-8')
    print(json.dumps({'completed':len(rows),'ok':sum(r['status']=='OK' for r in rows)}))


if __name__=='__main__':
    main()
