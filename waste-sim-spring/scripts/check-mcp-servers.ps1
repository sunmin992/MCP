# SessionStart 훅: .mcp.json 의 MCP 서버가 떠 있는지 본다.
# Claude Code 는 세션 시작 때 한 번만 MCP 에 연결하므로, 서버가 꺼져 있으면 그 세션 내내 도구가 없다.
$servers = @(
    @{ Name = 'waste-broker'; Port = 8089 },
    @{ Name = 'jangnyang-simulator'; Port = 8090 }
)

$down = @()
foreach ($s in $servers) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        if (-not $client.ConnectAsync('127.0.0.1', $s.Port).Wait(1000)) { $down += $s }
    } catch {
        $down += $s
    } finally {
        $client.Dispose()
    }
}

if ($down.Count -eq 0) { exit 0 }

$list = ($down | ForEach-Object { "$($_.Name)(:$($_.Port))" }) -join ', '
$msg = "MCP 서버가 꺼져 있습니다: $list. 서버를 띄운 뒤 /mcp 로 다시 연결하세요."
$context = "세션 시작 시점에 MCP 서버 $list 가 응답하지 않았다. 이 세션에서는 해당 MCP 도구가 없을 수 있다. REST API 를 직접 부르기 전에 사용자에게 서버를 띄우고 /mcp 로 다시 연결하라고 먼저 알린다."

@{
    systemMessage      = $msg
    hookSpecificOutput = @{ hookEventName = 'SessionStart'; additionalContext = $context }
} | ConvertTo-Json -Compress |
    # 콘솔 코드페이지와 상관없이 한글이 깨지지 않게 ASCII 로만 내보낸다
    ForEach-Object { [regex]::Replace($_, '[^\x00-\x7F]', { param($m) '\u{0:x4}' -f [int][char]$m.Value }) }
