"""Score exported LLM requirements; never substitutes gold for model predictions.

Input predictions JSONL: {id, run, model, promptVersion, requirements}.
This is a small implementation fixture set, not a research benchmark.
"""
import argparse
import json
from pathlib import Path


def leaves(value, prefix=''):
    if isinstance(value, dict):
        return {k: v for key, val in value.items() if key not in ('schemaVersion', 'evidence')
                for k, v in leaves(val, f'{prefix}.{key}' if prefix else key).items()}
    return {prefix: value}


def score(gold, predictions):
    seen = set()
    totals = dict(cases=0, exactCases=0, expectedFields=0, predictedFields=0, correctFields=0, inventedFields=0)
    versions = set()
    observed = {}
    for row in predictions:
        key = (row['id'], row['run'])
        if key in seen or row['id'] not in gold:
            raise ValueError(f'Duplicate or unknown prediction: {key}')
        seen.add(key)
        versions.add((row['model'], row['promptVersion']))
        expected = leaves(gold[row['id']]['expected'])
        if not isinstance(row['requirements'], dict):
            raise ValueError('requirements must be an object')
        actual = leaves(row['requirements'])
        actual.pop('', None)
        totals['cases'] += 1
        totals['exactCases'] += expected == actual
        totals['expectedFields'] += len(expected)
        totals['predictedFields'] += len(actual)
        totals['correctFields'] += sum(k in actual and actual[k] == v for k, v in expected.items())
        totals['inventedFields'] += len(actual.keys() - expected.keys())
        observed.setdefault(row['id'], []).append(json.dumps(actual, sort_keys=True, ensure_ascii=False))
    if not seen:
        raise ValueError('No model predictions; extraction accuracy was not measured')
    if len(versions) != 1:
        raise ValueError('Compare models/prompt versions in separate runs')
    totals['exactCaseRate'] = totals['exactCases'] / totals['cases']
    totals['fieldAccuracy'] = totals['correctFields'] / totals['expectedFields'] if totals['expectedFields'] else None
    totals['inventedFieldRate'] = totals['inventedFields'] / totals['predictedFields'] if totals['predictedFields'] else 0
    totals['missingCaseIds'] = sorted(gold.keys() - observed.keys())
    totals['unstableCaseIds'] = [k for k, v in observed.items() if len(set(v)) > 1]
    totals['model'], totals['promptVersion'] = next(iter(versions))
    return totals


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('predictions', type=Path)
    parser.add_argument('--gold', type=Path, default=Path(__file__).resolve().parents[1] /
                        'docs/reports/implementation-2026-11-03/extraction-gold.jsonl')
    args = parser.parse_args()
    gold = {r['id']: r for line in args.gold.read_text(encoding='utf-8').splitlines() if line.strip()
            for r in [json.loads(line)]}
    predictions = [json.loads(line) for line in args.predictions.read_text(encoding='utf-8').splitlines() if line.strip()]
    print(json.dumps(score(gold, predictions), ensure_ascii=False, indent=2))
