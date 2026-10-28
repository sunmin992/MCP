# 경주(47130) 시설 목록 쪽 진단 — 인증키와 요청 URL 은 출력하지 않는다.
# 인증키를 넣어 둔 PowerShell 창에서 실행: powershell -ExecutionPolicy Bypass -File C:\Dev\MCP\outputs\parking_gyeongju_diag.ps1
$key = $env:PARKING_API_SERVICE_KEY
if (-not $key) { Write-Host "PARKING_API_SERVICE_KEY 가 이 창에 없습니다."; exit 1 }
if ($key -match '%') { $key = [uri]::UnescapeDataString($key) }
$enc = [uri]::EscapeDataString($key)
$base = "https://apis.data.go.kr/B553881/Parking_v1/PrkSttusInfo_v1"
$sum = 0; $ids = @{}; $dup = 0
for ($page = 1; $page -le 200; $page++) {
    try { $r = Invoke-RestMethod -Uri "$base`?serviceKey=$enc&format=2&numOfRows=100&pageNo=$page&sigunguCd=47130" -TimeoutSec 20 }
    catch { Write-Host "쪽 $page 요청 실패: $($_.Exception.Response.StatusCode)"; break }
    if ($r -is [xml] -or $r -is [string]) { Write-Host "쪽 $page XML/문자열 응답"; break }
    $rows = @($r.PrkSttusInfo)
    foreach ($row in $rows) { if ($ids.ContainsKey($row.prk_center_id)) { $dup++ } else { $ids[$row.prk_center_id] = 1 } }
    $sum += $rows.Count
    if ($page -le 2 -or $rows.Count -lt 100) { Write-Host ("쪽 {0}: 행 {1}, 누적 {2}, totalCount {3}, 중복 누적 {4}" -f $page, $rows.Count, $sum, $r.totalCount, $dup) }
    if ($rows.Count -eq 0) {
        $again = @((Invoke-RestMethod -Uri "$base`?serviceKey=$enc&format=2&numOfRows=100&pageNo=$page&sigunguCd=47130" -TimeoutSec 20).PrkSttusInfo)
        Write-Host ("  같은 쪽 다시: 행 {0}" -f $again.Count)
        $next = @((Invoke-RestMethod -Uri "$base`?serviceKey=$enc&format=2&numOfRows=100&pageNo=$($page + 1)&sigunguCd=47130" -TimeoutSec 20).PrkSttusInfo)
        Write-Host ("  다음 쪽: 행 {0}" -f $next.Count)
        break
    }
    if ($sum -ge [int]$r.totalCount) { Write-Host "totalCount 까지 다 받음"; break }
}
Write-Host ("서로 다른 관리번호 {0}개" -f $ids.Count)
