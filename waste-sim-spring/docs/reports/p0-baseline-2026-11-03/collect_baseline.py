"""P0: collect checked-in contracts and current Surefire results; run from any cwd."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[2]
PROJECTS = {
    'jangnyang': ROOT / 'simulator',
    'parking': ROOT.parent / 'parking-sim',
    'counter': ROOT.parent / 'counter-sim',
    'district': ROOT.parent / 'district-waste-sim',
}
contracts = {}
for name, project in PROJECTS.items():
    resources = project / 'src/main/resources'
    files = [*resources.glob('mcp/*capability*.json'),
             *resources.glob('ses/*templates*.json'), resources / 'ses/task-manifest.json']
    contracts[name] = {str(p.relative_to(project)): json.loads(p.read_text(encoding='utf-8'))
                       for p in files}
(OUT / 'contract-snapshot.json').write_text(json.dumps(contracts, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

results = {}
modules = {'mcp-common': ROOT / 'mcp-common', 'broker': ROOT / 'broker',
           'template-generator': ROOT / 'template-generator', **PROJECTS}
for name, project in modules.items():
    reports = list((project / 'target/surefire-reports').glob('TEST-*.xml'))
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    problems = []
    for p in reports:
        suite = ET.parse(p).getroot()
        for key in counts:
            counts[key] += int(suite.get(key, '0'))
        for case in suite.findall('testcase'):
            for kind in ('failure', 'error', 'skipped'):
                node = case.find(kind)
                if node is not None:
                    problems.append(dict(test=case.get('classname') + '.' + case.get('name'),
                                         kind=kind, message=node.get('message', '')))
    results[name] = dict(reports=len(reports), **counts, problems=problems)
(OUT / 'test-summary.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
for name, result in results.items():
    print(name, {k: v for k, v in result.items() if k != 'problems'})
