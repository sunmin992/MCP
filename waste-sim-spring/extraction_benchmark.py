"""추출 전용 LLM + 실제 Java 검증/시나리오 조립 평가. 기본값은 로컬 Qwen."""
import argparse
import hashlib
import json
import os
import subprocess
import shutil
import time
import urllib.request
from pathlib import Path
import llm_benchmark as old

ROOT = Path(__file__).resolve().parent

def compact_prompt(fields):
    rows = []
    for name, item in fields.items():
        allowed = item['allowedRange']
        constraint = allowed.get('values', {k: v for k, v in allowed.items() if k != 'description'})
        rows.append(f"{name}: {item['answerType']} | {item['question'].split('(')[0].strip()} | {json.dumps(constraint, ensure_ascii=False)}")
    return '''사용자 문장에서 명시한 설정만 추출하는 JSON 추출기다.
숫자는 JSON 숫자, 시각은 HH:MM 문자열, 목록은 배열로 쓴다. span은 사용자 원문 그대로 2글자 이상 인용한다.
잘못된 값도 그대로 추출한다. 허용 범위를 넘거나 미지원 엔진이어도 수정·삭제하지 않는다.
기본값 적용 동의는 defaultApproval=ALL로 추출하되 기본값 자체를 만들지 않는다.
기본값·질문·valid 판정·시나리오는 서버가 만든다. 출력하지 않는다.
허용 범위는 해석 참고이며 출력값을 그 범위로 보정하지 않는다.
필드 정의:
''' + '\n'.join(rows) + '''
예시 요청: 건물 6개, 2주 동안, 민원 기준 75%로 해줘
예시 출력: {"values":[{"field":"numBuildings","value":6,"span":"건물 6개"},{"field":"days","value":14,"span":"2주"},{"field":"threshold","value":0.75,"span":"75%"}]}
예시 요청: 시뮬레이터 만들어줘
예시 출력: {"values":[]}
예시 요청: 건물 99개, 엔진 rust로
예시 출력: {"values":[{"field":"numBuildings","value":99,"span":"건물 99개"},{"field":"engine","value":"rust","span":"엔진 rust"}]}
출력 JSON 객체는 values만 갖는다. 각 원소는 field,value,span만 갖는다. 설명문은 쓰지 않는다.'''

def extraction_schema(fields):
    variants = []
    for name, item in fields.items():
        kind = item['answerType']
        value = {'type': 'number' if kind == 'NUMBER' else 'integer' if kind == 'INTEGER' else 'string'}
        if kind in ('STRING_LIST', 'TIME_LIST'):
            value = {'type':'array', 'items':{'type':'string'}}
        # 범위·enum을 강제하면 잘못된 사용자 값을 원형으로 추출할 수 없다.
        variants.append({'type':'object', 'properties':{'field':{'const':name}, 'value':value,
                         'span':{'type':'string','minLength':2}},
                         'required':['field','value','span'], 'additionalProperties':False})
    return {'type':'object','properties':{'values':{'type':'array','items':{'anyOf':variants},'maxItems':len(fields)}},
            'required':['values'],'additionalProperties':False}

def request_json(url, body=None, timeout=240):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(url, data=data, headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.load(response)

def infer(base, model, prompt, request, schema, context):
    start = time.perf_counter()
    payload = request_json(base+'/api/chat', {'model':model,'stream':False,
        'messages':[{'role':'system','content':prompt},{'role':'user','content':request}],
        'format':schema if schema else 'json',
        'options':{'temperature':0,'seed':42,'num_ctx':context,'num_predict':2048}})
    return {'raw':payload['message']['content'],'latency':time.perf_counter()-start,
            'actualModel':payload.get('model'), 'doneReason':payload.get('done_reason'),
            'promptTokens':payload.get('prompt_eval_count'), 'outputTokens':payload.get('eval_count'),
            'loadDuration':payload.get('load_duration')}

def extraction_errors(out, fields):
    if not isinstance(out,dict) or set(out) != {'values'} or not isinstance(out['values'],list):
        return ['values 배열만 갖는 객체 필요']
    errors=[]; seen=set()
    for row in out['values']:
        if not isinstance(row,dict) or set(row) != {'field','value','span'}:
            errors.append('field/value/span 계약 오류'); continue
        f=row['field']
        if not isinstance(f,str) or f not in fields:
            errors.append('미등록 필드'); continue
        if f in seen: errors.append('중복 필드 '+f)
        seen.add(f)
        if not isinstance(row['span'],str) or len(row['span'])<2: errors.append('span 타입/길이')
        k=fields[f]['answerType']; v=row['value']
        ok = (type(v) is int if k=='INTEGER' else type(v) in (int,float) if k=='NUMBER'
              else isinstance(v,list) and all(isinstance(x,str) for x in v) if k in ('STRING_LIST','TIME_LIST')
              else isinstance(v,str))
        if not ok: errors.append('value 자료형 '+f)
    return errors

def metrics(case, out, fields):
    errors=extraction_errors(out, fields)
    expected={**case.get('expected',{}),**case.get('invalid',{})}
    rows={r['field']:r for r in out['values']} if not errors else {}
    good=sum(f in rows and old.same(rows[f]['value'],v) and old.span_ok(case['request'],rows[f]['span']) for f,v in expected.items())
    extra=sorted(set(rows)-set(expected))
    return dict(contract=not errors, errors=errors, correct=good, expected=len(expected),
                extra=extra, extractionPass=not errors and good==len(expected) and not extra)

def cases():
    result=[dict(c, split='existing') for c in old.CASES]
    result += [
        dict(id='heldout-scale-a',request='건물 5개에 각 42명, 19일간 8회 반복해줘.',expected=dict(numBuildings=5,residentsPerBuilding=42,days=19,seeds=8),split='heldout'),
        dict(id='heldout-scale-b',request='건물 9개에 각 42명, 19일간 8회 반복해줘.',expected=dict(numBuildings=9,residentsPerBuilding=42,days=19,seeds=8),split='heldout'),
        dict(id='heldout-invalid',request='트럭 0대, 수거 시각은 23:59로 해줘.',expected=dict(collectionTime='23:59'),invalid=dict(truckCount=0),split='heldout')]
    return result

def followups(case):
    # 사전에 정한 추가 사용자 답변. 요청에 명시한 필드를 보충하거나 덮지 않는다.
    result=dict(simulationGoal='민원 비교',scenarioType='single-run',defaultApproval='ALL',
                serviceMinutesPerSite=3,intraZoneTravelMinutes=5,zoneAssignmentRule='NONE')
    for f in {**case.get('expected',{}),**case.get('invalid',{})}: result.pop(f,None)
    return result

def bridge(records, path, repo):
    source=path/'java-input.json'; target=path/'java-output.json'
    source.write_text(json.dumps(records,ensure_ascii=False),encoding='utf-8')
    maven = shutil.which('mvn.cmd' if os.name == 'nt' else 'mvn')
    if not maven: raise RuntimeError('Maven executable not found')
    command=[maven,'-o','-q',f'-Dmaven.repo.local={repo}','-Dtest=ExtractionBenchmarkBridgeTest',
             f'-Dbenchmark.input={source}',f'-Dbenchmark.output={target}','test']
    with (path/'java.log').open('w',encoding='utf-8') as log:
        subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,check=True)
    return {r['id']:r for r in json.loads(target.read_text(encoding='utf-8'))}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--model',default='qwen2.5:7b'); parser.add_argument('--runs',type=int,default=1)
    parser.add_argument('--base',default='http://localhost:11434'); parser.add_argument('--context',type=int,default=8192)
    parser.add_argument('--repo',default=str(Path.home()/'.m2/repository'))
    args=parser.parse_args()
    if args.runs<1: parser.error('--runs must be positive')
    t,fields=old.load_template(); prompt=compact_prompt(fields); schema=extraction_schema(fields)
    path=ROOT/'benchmark_runs'/time.strftime('%Y%m%d-%H%M%S'); path.mkdir(parents=True)
    meta={'model':args.model,'contextRequested':args.context,'templateHash':hashlib.sha256(old.TEMPLATE_PATH.read_bytes()).hexdigest(),
          'promptHash':hashlib.sha256(prompt.encode()).hexdigest(),'promptChars':len(prompt),
          'baselinePromptChars':len(old.prompt_from(t,fields)), 'version':request_json(args.base+'/api/version'),
          'show':request_json(args.base+'/api/show',{'model':args.model})}
    (path/'prompt.txt').write_text(prompt,encoding='utf-8')
    (path/'schema.json').write_text(json.dumps(schema,ensure_ascii=False,indent=2),encoding='utf-8')
    probe=infer(args.base,args.model,prompt,'건물 11개',schema,args.context)
    obj=old.parse(probe['raw']); errors=extraction_errors(obj,fields)
    if errors: raise RuntimeError('JSON Schema probe failed: '+str(errors))
    meta['schemaProbe']=probe; meta['loaded']=request_json(args.base+'/api/ps')
    (path/'metadata.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8')
    records=[]; batch=[]
    for case in cases():
        expected={**case.get('expected',{}),**case.get('invalid',{})}
        batch.append(dict(id='oracle:'+case['id'],request=case['request'],
                          values=[dict(field=f,value=v,span=case['request']) for f,v in expected.items()],followups=followups(case)))
        for mode in ('baseline','extraction'):
            for repeat in range(args.runs):
                key=f'{mode}:{case["id"]}:{repeat}'
                print(key,flush=True)
                row=dict(id=key,case=case['id'],mode=mode,split=case['split'])
                try:
                    response=infer(args.base,args.model,old.prompt_from(t,fields) if mode=='baseline' else prompt,
                                   case['request'],None if mode=='baseline' else schema,args.context)
                    row.update(response)
                    output=old.parse(response['raw'])
                    # 개선 전 구조는 원래 계약을 먼저 검사한 뒤 추출값만 비교한다.
                    legacy_errors=old.contract_errors(output) if mode=='baseline' else []
                    extracted={'values':[{k:r[k] for k in ('field','value','span')} for r in output['values']]} if mode=='baseline' and not legacy_errors else output
                    score=metrics(case,extracted,fields) if not legacy_errors else dict(contract=False,errors=legacy_errors,correct=0,expected=len(expected),extra=[],extractionPass=False)
                    row['score']=score
                    if score['contract']:
                        batch.append(dict(id=key,request=case['request'],values=extracted['values'],followups=followups(case)))
                except (ValueError,KeyError,TypeError) as exc: row['outputError']=str(exc)
                except (OSError,TimeoutError) as exc: row['callError']=old.call_error_text(exc)
                records.append(row)
                with (path/'responses.jsonl').open('a',encoding='utf-8') as log:log.write(json.dumps(row,ensure_ascii=False)+'\n')
    downstream=bridge(batch,path,args.repo)
    finish(records, downstream, path, meta, args.model, args.context, args.runs)

def finish(records, downstream, path, meta, model, context, runs):
    for row in records:
        actual=downstream.get(row['id']); oracle=downstream['oracle:'+row['case']]
        row['oracleBuild']=oracle['finalBuild']
        if actual:
            row['pipeline']=actual
            row['questionsMatch']=set(actual['mustAsk'])==set(oracle['mustAsk'])
            row['extraQuestions']=sorted(set(actual['mustAsk'])-set(oracle['mustAsk']))
            row['scenarioMatch']=actual['finalBuild'] and oracle['finalBuild'] and actual['finalAnswers']==oracle['finalAnswers']
            row['pipelinePass']=row['score']['extractionPass'] and row['questionsMatch'] and actual['finalBuild']==oracle['finalBuild'] and (not oracle['finalBuild'] or row['scenarioMatch'])
    (path/'results.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
    lines=['# 추출 전용 모델 개선 전후 비교','',f'모델: {model}, 컨텍스트 요청: {context}, 반복: {runs}',
           f'프롬프트 문자 수: {meta["baselinePromptChars"]} → {meta["promptChars"]}','',
           '| 세트 | 방식 | 응답/시도 | 추출 계약 | 정확값+근거 | 질문 일치 | 생성 대상 시나리오 일치 | 전체 흐름 일치 |',
           '|---|---|---|---|---|---|---|---|']
    for split in ('existing','heldout'):
        for mode in ('baseline','extraction'):
            rs=[r for r in records if r['mode']==mode and r['split']==split]; n=len(rs)
            eligible=sum(r['oracleBuild'] for r in rs)
            total=sum(len({**c.get('expected',{}),**c.get('invalid',{})}) for c in cases() if c['split']==split)*runs
            lines.append(f'| {split} | {mode} | {sum("raw" in r for r in rs)}/{n} | {sum(r.get("score",{}).get("contract",False) for r in rs)}/{n} | {sum(r.get("score",{}).get("correct",0) for r in rs)}/{total} | {sum(r.get("questionsMatch",False) for r in rs)}/{n} | {sum(r.get("scenarioMatch",False) for r in rs)}/{eligible} | {sum(r.get("pipelinePass",False) for r in rs)}/{n} |')
    lines += ['', '시나리오는 실제 Java 조립기가 생성한다. 고정된 추가 사용자 답변은 java-input.json에 명시했다.',
              '잘못된 값은 수정 답변을 제공하지 않으므로 생성 차단이 정답이다. 생성 대상 분모는 정답 추출값으로 실제 조립 가능한 사례만 포함한다.',
              '호출/출력 실패도 비교 분모에 포함한다. heldout은 프롬프트 예시에 없는 요청이다.',
              '메타데이터에는 서버 버전·모델 정보·실제 로딩 상태, 응답별 입력/출력 토큰 수와 종료 사유를 저장했다.',
              '이 평가는 장량동 파라미터 구성 범위이며 지역/분야 라우팅과 실제 시뮬레이션 엔진 실행 성능은 포함하지 않는다.']
    (path/'report.md').write_text('\n'.join(lines),encoding='utf-8')
    print('RESULT',path,flush=True)

if __name__=='__main__':main()
