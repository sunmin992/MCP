<#
.SYNOPSIS
    PowerShell에서 MCP 서버(POST /mcp)를 호출하는 헬퍼.

.DESCRIPTION
    Windows PowerShell에서 curl/jq 예제를 그대로 쓰면 세 가지가 걸린다.
      1) curl 이 Invoke-WebRequest 별칭이라 -d/-H 옵션을 모른다(curl.exe 로 써야 진짜 curl)
      2) jq 가 없다
      3) PowerShell 5.1의 Invoke-RestMethod 는 Content-Type 에 charset 이 없으면
         응답을 UTF-8로 읽지 않아 한글 notes/warnings 가 깨진다

    이 스크립트는 셋 다 우회한다 — 응답 바이트를 직접 UTF-8로 디코드하고,
    MCP 규격상 문자열로 한 번 더 감싸여 오는 result.content[0].text 를 풀어
    PowerShell 객체로 돌려준다.

.EXAMPLE
    . .\scripts\edge\Invoke-Mcp.ps1          # 점(.) 붙여서 현재 세션에 함수 로드

    Get-McpTools                             # 도구 목록

    $r = Invoke-Mcp simulate_edge_throttling @{
        board='pi5'; cooling='bare'; ambientTempC=35
        workloadMode='max_throughput'; loadSeconds=900
        recoveryPolicy='r1_stop'; recoverySeconds=600; sampleIntervalSeconds=15
    }
    $r.metrics
    $r.notes
#>

function Invoke-McpRaw {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Method,
        [hashtable]$Params,
        [string]$Url = 'http://localhost:8090/mcp',
        [int]$Depth = 12,
        [int]$TimeoutSec = 120
    )

    $request = @{ jsonrpc = '2.0'; id = 1; method = $Method }
    if ($Params) { $request.params = $Params }

    # ConvertTo-Json 은 기본 Depth 가 2라 layouts 같은 중첩 구조가 잘린다 — Depth 를 넉넉히.
    $json = $request | ConvertTo-Json -Depth $Depth -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)

    $resp = Invoke-WebRequest -Uri $Url -Method Post -Body $bytes `
        -ContentType 'application/json; charset=utf-8' `
        -TimeoutSec $TimeoutSec -UseBasicParsing

    # 응답을 바이트로 받아 UTF-8로 직접 디코드(PS 5.1 한글 깨짐 방지)
    $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    $text | ConvertFrom-Json
}

function Get-McpTools {
    [CmdletBinding()]
    param([string]$Url = 'http://localhost:8090/mcp', [switch]$Detail)

    $body = Invoke-McpRaw -Method 'tools/list' -Url $Url
    if ($Detail) { $body.result.tools | Select-Object name, description }
    else { $body.result.tools.name }
}

function Invoke-Mcp {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory, Position = 0)][string]$Tool,
        [Parameter(Position = 1)][hashtable]$Arguments = @{},
        [string]$Url = 'http://localhost:8090/mcp',
        [int]$Depth = 12,
        [int]$TimeoutSec = 120
    )

    $body = Invoke-McpRaw -Method 'tools/call' -Url $Url -Depth $Depth -TimeoutSec $TimeoutSec `
        -Params @{ name = $Tool; arguments = $Arguments }

    if ($body.error) { throw "MCP 프로토콜 오류: $($body.error.message)" }

    $payload = $body.result.content[0].text
    if ($body.result.isError) {
        # 검증 실패는 예외가 아니라 정상적인 결과다 — 어떤 인자가 왜 거부됐는지 그대로 보여준다.
        Write-Warning "도구가 요청을 거부했다(fail-closed):`n$payload"
        return $payload
    }
    $payload | ConvertFrom-Json
}

function Import-McpCalibration {
    <#
    .SYNOPSIS
        측정 CSV를 그대로 calibrate_edge_thermal_model 로 보낸다.
    .EXAMPLE
        Import-McpCalibration -CsvPath .\runs\pi5-passive-0803-142530.csv -Board pi5 -AmbientC 26.5 -LoadEndSec 1200
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$CsvPath,
        [Parameter(Mandatory)][ValidateSet('pi4', 'pi5')][string]$Board,
        [Parameter(Mandatory)][double]$AmbientC,
        [double]$LoadEndSec,
        [string]$Label,
        [string]$Url = 'http://localhost:8090/mcp'
    )

    $rows = Import-Csv $CsvPath
    # 유휴(BASELINE) 구간은 상승 곡선 적합을 망가뜨리므로 잘라내고 부하 시작을 t=0으로 재설정
    $firstLoad = $rows | Where-Object { $_.phase -eq 'LOAD' } | Select-Object -First 1
    $shift = if ($firstLoad) { [double]$firstLoad.t_sec } else { 0 }

    $lines = @('t_sec,soc_temp_c,power_w,clock_mhz,fps,throttled')
    foreach ($r in $rows) {
        if (-not $r.t_sec -or -not $r.soc_temp_c) { continue }
        $t = [double]$r.t_sec - $shift
        if ($t -lt 0) { continue }
        $lines += ('{0:F1},{1},{2},{3},{4},{5}' -f $t, $r.soc_temp_c, $r.power_w, $r.clock_mhz, $r.fps, $r.throttled)
    }

    $arguments = @{
        board        = $Board
        ambientTempC = $AmbientC
        label        = if ($Label) { $Label } else { [IO.Path]::GetFileNameWithoutExtension($CsvPath) }
        samplesCsv   = ($lines -join "`n")
    }
    if ($PSBoundParameters.ContainsKey('LoadEndSec')) { $arguments.loadEndSeconds = $LoadEndSec - $shift }

    Invoke-Mcp -Tool 'calibrate_edge_thermal_model' -Arguments $arguments -Url $Url
}
