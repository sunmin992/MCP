# 주차 API 진단 — 인증키와 요청 URL 은 출력하지 않는다.
# 인증키를 넣어 둔 PowerShell 창에서 실행: powershell -ExecutionPolicy Bypass -File C:\Dev\MCP\outputs\parking_api_diag.ps1
$key = $env:PARKING_API_SERVICE_KEY
if (-not $key) { Write-Host "PARKING_API_SERVICE_KEY 가 이 창에 없습니다."; exit 1 }
if ($key -match '%') { $key = [uri]::UnescapeDataString($key) }
$enc = [uri]::EscapeDataString($key)
$base = "https://apis.data.go.kr/B553881/Parking_v1"

function Probe($label, $op, $extra) {
    $u = "$base/${op}_v1?serviceKey=$enc&format=2&numOfRows=100&pageNo=1$extra"
    try {
        $r = Invoke-RestMethod -Uri $u -TimeoutSec 20
    } catch {
        Write-Host ("{0,-28} 요청 실패: {1}" -f $label, $_.Exception.Response.StatusCode); return
    }
    # XML 오류면 사유 코드(대문자 · 밑줄)만 보인다. 본문은 출력하지 않는다.
    $text = if ($r -is [xml]) { $r.OuterXml } elseif ($r -is [string]) { $r } else { $null }
    if ($text) {
        $codes = [regex]::Matches($text, '<(returnAuthMsg|errMsg|returnReasonCode|resultCode|resultMsg)>\s*([A-Z0-9_]{2,60})\s*</') | ForEach-Object { $_.Groups[2].Value } | Select-Object -Unique
        Write-Host ("{0,-28} XML 오류 사유: {1}" -f $label, ($codes -join ' · ')); return
    }
    $rows = @($r.$op)
    Write-Host ("{0,-28} resultCode={1} totalCount={2} 이번쪽행수={3}" -f $label, $r.resultCode, $r.totalCount, $rows.Count)
    if ($rows.Count -gt 0) {
        $first = $rows[0]
        $fields = ($first.PSObject.Properties | ForEach-Object { $_.Name }) -join ', '
        Write-Host "    첫 행 필드: $fields"
        $first.PSObject.Properties | Where-Object { $_.Name -match 'adres|addr|sido|sigungu|cd$|manage' } |
            ForEach-Object { Write-Host ("    {0} = {1}" -f $_.Name, $_.Value) }
    }
}

Probe "시설 · 필터 없음"           "PrkSttusInfo" ""
Probe "시설 · sigunguCd=11110"      "PrkSttusInfo" "&sigunguCd=11110"
Probe "시설 · sigunguCd=47113"      "PrkSttusInfo" "&sigunguCd=47113"
Probe "시설 · sidoCd=47"            "PrkSttusInfo" "&sidoCd=47"
Probe "시설 · sido 47 + sigungu"    "PrkSttusInfo" "&sidoCd=47&sigunguCd=47113"
Probe "실시간 · sigunguCd=47113"    "PrkRealtimeInfo" "&sigunguCd=47113"
