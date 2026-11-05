"""R1 error injection: corrupt a correct requirement profile and follow it through broker -> scenario build.

Base profiles are model outputs that exactly matched gold (they carry real evidence quotes).
Each injected profile is compared with its base, so the verdict is about the injected error alone:
  CAUGHT_*  : blocked or asked before an executable scenario exists
  MISSED_*  : reached a different candidate set or a different valid configuration
  HARMLESS  : same outcome as the base
Only jangnyang derives a configuration from requirements by itself (create_neighborhood_scenario);
other providers need an agent to fill values, so their build stage is reported as BUILD_NOT_CHECKED.
Never approves or runs a scenario.
"""
import copy
import json
import re
from pathlib import Path
from demo_requirement_flow import tool
from score_requirement_model_evaluation import candidates, GOLD

ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / 'docs/reports'
OUT = REPORTS / 'llm-robustness-2026-11-05'
SOURCES = ['model-evaluation-ollama-qwen3-coder-30b', 'model-evaluation-ollama-qwen2.5-7b']
DEFAULTS = '나머지는 기본값으로 해줘'
METRIC = {'waste-collection': '평균 민원 수', 'public-parking': '평균 주차 탐색 시간',
          'civil-service-counter': '평균 대기 시간'}


def req(p):
    return p.setdefault('requirements', {})


def ev(p):
    return req(p).setdefault('evidence', {})


def unit_swap(p, u):
    d = req(p).get('simulationDuration')
    if not d or d['unit'] not in ('day', 'week'):
        return None
    d['unit'] = 'week' if d['unit'] == 'day' else 'day'
    return p


def value_shift(p, u):
    d = req(p).get('simulationDuration')
    if d:
        d['value'] += 1
        return p
    for k in ('constraints', 'preferences'):
        if req(p).get(k):
            req(p)[k][0]['value'] += 1
            return p
    return None


def duration_to_resolution(p, u):
    if 'simulationDuration' not in req(p):
        return None
    req(p)['timeResolution'] = req(p).pop('simulationDuration')
    if 'simulationDuration' in ev(p):
        ev(p)['timeResolution'] = ev(p).pop('simulationDuration')
    return p


def move_condition(src, dst):
    def inject(p, u):
        items = req(p).pop(src, None)
        if not items:
            return None
        req(p)[dst] = items
        for c in items:
            q = ev(p).pop(f'{src}.{c["name"]}', None)
            if q is not None:
                ev(p)[f'{dst}.{c["name"]}'] = q
        return p
    return inject


def drop(field):
    def inject(p, u):
        if field not in req(p):
            return None
        req(p).pop(field)
        ev(p).pop(field, None)
        return p
    return inject


def invent_output(p, u):
    # 30b added unrequested outputs 26 times; it quoted the whole utterance as evidence.
    if req(p).get('requiredOutputs') or p['domain'] not in METRIC:
        return None
    req(p)['requiredOutputs'] = [METRIC[p['domain']]]
    ev(p)['requiredOutputs'] = u
    return p


def invent_duration(p, u):
    if 'simulationDuration' in req(p):
        return None
    req(p)['simulationDuration'] = {'value': 1, 'unit': 'week'}
    ev(p)['simulationDuration'] = u
    return p


ERRORS = {
    'unit_swap': unit_swap,
    'value_shift': value_shift,
    'duration_to_resolution': duration_to_resolution,
    'demote_constraint': move_condition('constraints', 'preferences'),
    'promote_preference': move_condition('preferences', 'constraints'),
    'drop_region': drop('regionId'),
    'drop_output': drop('requiredOutputs'),
    'invent_output': invent_output,
    'invent_duration': invent_duration,
}


def broker(profile, utterance):
    request = copy.deepcopy(profile)
    request['userUtterances'] = [utterance]
    reply = tool(8089, 'find_simulators', request)
    return None if reply.get('toolError') else candidates(reply)


def build(profile, utterance):
    """Jangnyang build where the user answers every open question with '기본값으로'. Returns config or None."""
    consent = {}
    for _ in range(10):
        reply = tool(8090, 'create_neighborhood_scenario', {
            'request': utterance, 'requirements': profile.get('requirements', {}),
            'userUtterances': [utterance, DEFAULTS], 'evidence': consent})
        if not reply.get('toolError'):
            break
        asked = re.findall(r'(\w+): 사용자에게 묻거나', reply['response']['result']['content'][0]['text'])
        if not asked or all(k in consent for k in asked):
            return None
        consent.update({k: DEFAULTS for k in asked})
    if not reply.get('valid') or reply.get('state') == 'NEEDS_INPUT':
        return None
    config = {}
    for part in reply['decisionRoles'].values():
        config.update(part)
    config['observations'] = sorted(reply.get('purposeCheck', {}).get('specification', {}).get('observations') or [])
    return config


def classify(base_cands, cands, base_cfg, cfg_fn):
    if cands is None:
        return 'CAUGHT_EVIDENCE'
    if base_cands and not cands:
        return 'CAUGHT_NO_CANDIDATE'
    if not base_cands:
        return 'HARMLESS' if not cands else 'MISSED_ACCEPTED'
    if cands != base_cands:
        return 'MISSED_OTHER_CANDIDATES'
    if 'jangnyang-waste-sim' not in cands:
        return 'BUILD_NOT_CHECKED'
    cfg = cfg_fn()
    if cfg is None:
        return 'CAUGHT_BUILD' if base_cfg is not None else 'HARMLESS'
    return 'HARMLESS' if cfg == base_cfg else 'MISSED_CONFIG'


def bases():
    gold = {r['id']: r for line in GOLD.read_text(encoding='utf-8').splitlines() if line.strip()
            for r in [json.loads(line)]}
    picked = {}
    for source in SOURCES:
        metrics = json.loads((REPORTS / source / 'extraction-and-selection-metrics.json').read_text(encoding='utf-8'))
        exact = {(r['id'], r['run']) for r in metrics['modelRuns'] if r['exactProfile']}
        for line in (REPORTS / source / 'predictions.jsonl').read_text(encoding='utf-8').splitlines():
            row = json.loads(line)
            if (row['id'], row['run']) in exact and row['id'] not in picked:
                picked[row['id']] = dict(source=source, run=row['run'], profile=row['profile'],
                                         utterance=gold[row['id']]['utterance'])
    return dict(sorted(picked.items()))


def main():
    rows = []
    for case_id, base in bases().items():
        u, profile = base['utterance'], base['profile']
        base_cands = broker(profile, u)
        base_cfg = build(profile, u) if base_cands and 'jangnyang-waste-sim' in base_cands else None
        for name, inject in ERRORS.items():
            bad = inject(copy.deepcopy(profile), u)
            if bad is None:
                continue
            cands = broker(bad, u)
            outcome = classify(base_cands, cands, base_cfg, lambda: build(bad, u))
            rows.append(dict(id=case_id, error=name, outcome=outcome, utterance=u, baseSource=base['source'],
                             baseCandidates=base_cands, candidates=cands, injected=bad.get('requirements')))
            print(case_id, name, outcome, flush=True)
    table = {}
    for r in rows:
        table.setdefault(r['error'], {}).setdefault(r['outcome'], 0)
        table[r['error']][r['outcome']] += 1
    totals = {}
    for r in rows:
        totals[r['outcome']] = totals.get(r['outcome'], 0) + 1
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'error-injection.json').write_text(json.dumps(
        dict(baseCases=len({r['id'] for r in rows}), injections=len(rows), totals=totals, byError=table, runs=rows),
        ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(totals=totals, byError=table), ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
