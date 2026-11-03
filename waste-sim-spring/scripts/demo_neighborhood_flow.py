"""Verify arbitrary-neighborhood creation via local MCP. Never grants user approval."""
import json
from pathlib import Path
from demo_requirement_flow import tool


def main():
    report = {}
    req = {'regionId': '덕명동', 'simulationDuration': {'value': 2, 'unit': 'day'}}
    report['match'] = tool(8089, 'find_simulators', {'domain': '쓰레기수거', 'requirements': req})
    assert report['match']['verdict'] == 'MATCH'
    assert report['match']['scenarioCreationTool'] == 'create_neighborhood_scenario'
    region_check = next(c for c in report['match']['recommended']['conditionChecks'] if c['field'] == 'regionId')
    assert region_check['status'] == 'CONDITIONAL'
    report['deokmyeong'] = tool(8090, 'create_neighborhood_scenario', {
        'request': '덕명동 시뮬레이터 실행해줘', 'selection': report['match']['selection'],
        'values': {'seeds': 1}})
    first = report['deokmyeong']
    assert first['valid'] and first['state'] == 'UNCONFIRMED' and 'confirmToken' not in first
    assert first['region']['id'] == '덕명동' and first['scenarioMode'] == 'ASSUMED_BLOCK'
    assert first['region']['synthetic'] and 'confirm.html?id=' in first['confirmUrl']
    report['status'] = tool(8090, 'get_scenario_status', {'scenarioId': first['scenarioId']})
    assert report['status']['dataNotice'] and report['status']['scenarioMode'] == 'ASSUMED_BLOCK'
    report['unapprovedRun'] = tool(8090, 'run_scenario_by_token', {'scenarioId': first['scenarioId']})
    assert report['unapprovedRun']['toolError']
    report['anyNeighborhood'] = tool(8090, 'create_neighborhood_scenario', {'request': '아무 동네 시뮬레이터 실행해줘'})
    assert report['anyNeighborhood']['valid'] and report['anyNeighborhood']['region']['id'] == '가상 동네'
    report['otherName'] = tool(8090, 'create_neighborhood_scenario', {
        'request': '처음 보는 지역에서 실행해줘', 'regionName': '처음 등록하지 않은 동네',
        'values': {'days': 2, 'seeds': 1, 'numBuildings': 8, 'truckCount': 2}})
    assert report['otherName']['valid']
    report['foreignTraffic'] = tool(8090, 'create_neighborhood_scenario', {
        'request': '덕명동 교통 반영', 'regionName': '덕명동', 'values': {'trafficMode': 'APPLY'}})
    assert not report['foreignTraffic'].get('valid', False)
    report['tooLarge'] = tool(8090, 'create_neighborhood_scenario', {
        'request': '덕명동 건물 100개로 실행', 'regionName': '덕명동', 'values': {'numBuildings': 100}})
    assert report['tooLarge']['state'] == 'NEEDS_INPUT' and not report['tooLarge']['valid']
    report['wholeDistrict'] = tool(8089, 'find_simulators', {
        'domain': '쓰레기수거', 'spatialScale': '동 전체', 'requirements': {'regionId': '덕명동'}})
    assert report['wholeDistrict']['verdict'] != 'MATCH'
    out = Path(__file__).resolve().parents[1] / 'docs/reports/neighborhood-generalization-2026-11-03/live-demo.json'
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('PASS: 9 neighborhood flow cases; all live scenarios remain unapproved')
    print('Confirmation preview: ' + first['confirmUrl'])


if __name__ == '__main__':
    main()
