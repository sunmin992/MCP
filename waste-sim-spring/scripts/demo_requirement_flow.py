"""Read/build-only local integration demo. Starts no servers and grants no approvals."""
import json
from pathlib import Path
import urllib.request

OUT = Path(__file__).resolve().parents[1] / 'docs/reports/implementation-2026-11-03/live-demo.json'


def tool(port, name, args):
    payload = dict(jsonrpc='2.0', id=1, method='tools/call', params=dict(name=name, arguments=args))
    req = urllib.request.Request(f'http://127.0.0.1:{port}/mcp',
                                 data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=20) as response:
        rpc = json.load(response)
    result = rpc.get('result', {})
    if result.get('isError'):
        return dict(toolError=True, response=rpc)
    value = json.loads(result['content'][0]['text'])
    if isinstance(value, str):
        value = json.loads(value)  # Existing simulator transport serializes its JSON string again.
    return value


def main():
    report = {}
    report['twoDayMatch'] = tool(8089, 'find_simulators', dict(domain='쓰레기수거', requirements={
        'schemaVersion': '1.0.0', 'simulationDuration': {'value': 2, 'unit': 'day'}}))
    assert report['twoDayMatch']['verdict'] == 'MATCH'
    assert report['twoDayMatch']['recommended']['serverId'] == 'jangnyang-waste-sim'
    report['districtMatch'] = tool(8089, 'find_simulators', dict(domain='쓰레기수거', spatialScale='행정동',
        requirements={'simulationDuration': {'value': 14, 'unit': 'day'}}))
    assert report['districtMatch']['verdict'] == 'MATCH'
    assert report['districtMatch']['recommended']['serverId'] == 'bukgu-district-waste-sim'
    report['choose'] = tool(8089, 'find_simulators', dict(domain='쓰레기수거',
        requirements={'simulationDuration': {'value': 14, 'unit': 'day'}}))
    assert report['choose']['verdict'] == 'CHOOSE'
    report['unknownResolution'] = tool(8089, 'find_simulators', dict(domain='쓰레기수거',
        requirements={'timeResolution': {'value': 1, 'unit': 'hour'}}))
    assert report['unknownResolution']['verdict'] != 'MATCH'
    report['syntheticMatch'] = tool(8089, 'find_simulators', dict(domain='쓰레기수거', requirements={
        'regionId': 'synthetic-block', 'simulationDuration': {'value': 2, 'unit': 'day'},
        'requiredOutputs': ['meanComplaints']}))
    assert report['syntheticMatch']['verdict'] == 'MATCH'
    args = dict(request='합성 블록에서 이틀간 수거 시각 비교', regionId='synthetic-block',
                selection=report['syntheticMatch']['selection'],
                values=dict(truckType='SMALL_1TON', truckCount=1, days=2, seeds=1, trafficMode='IGNORE',
                            travelTimeMode='LEGACY_CONSTANT', occupationMix=['Student'],
                            routeAvailableCapacityKg=150, binCapacityKg=30, complaintThreshold=.8,
                            dischargeTimeMode='PAPER_BASELINE'),
                variableAnswerKey='collectionTimeMinutes', variableValues=[360, 1080], observations=['meanComplaints'])
    report['syntheticBuild'] = tool(8090, 'build_scenario', args)
    assert report['syntheticBuild']['valid']
    assert report['syntheticBuild']['region']['synthetic']
    report['unconfirmedRun'] = tool(8090, 'run_scenario_by_token', dict(scenarioId=report['syntheticBuild']['scenarioId']))
    assert report['unconfirmedRun']['toolError']
    # A failed revision must invalidate the preceding validation for the same request.
    args['requestId'] = report['syntheticBuild']['requestId']
    args['values']['days'] = 3
    report['durationMismatch'] = tool(8090, 'build_scenario', args)
    assert not report['durationMismatch']['valid']
    report['staleStatus'] = tool(8090, 'get_scenario_status', dict(scenarioId=report['syntheticBuild']['scenarioId']))
    assert report['staleStatus']['state'] == 'STALE'
    args['regionId'] = 'jangnyang'
    args.pop('selection')
    args['values']['days'] = 2
    args['values']['numBuildings'] = 4
    args['values']['residentsPerBuilding'] = 25
    report['jangnyangBuild'] = tool(8090, 'build_scenario', args)
    assert report['jangnyangBuild']['valid']
    args['values'].pop('truckType')
    report['missingInput'] = tool(8090, 'build_scenario', args)
    assert not report['missingInput'].get('valid', False)
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('PASS: 11 local integration cases; scenarios remain unapproved')


if __name__ == '__main__':
    main()
