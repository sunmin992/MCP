#!/usr/bin/env node
/*
 * waste-sim-spring MCP stdio 브리지
 * ───────────────────────────────────────────────────────────
 * Claude Desktop은 MCP 서버와 stdio(표준입출력)로 통신하지만, 이 프로젝트의
 * MCP 서버는 HTTP JSON-RPC 엔드포인트(POST /mcp)로 동작한다. 이 브리지가
 * 그 사이를 잇는다:
 *   stdin(줄 단위 JSON-RPC) → POST http://localhost:8080/mcp → stdout
 *
 * 의존성 없음(Node 내장 http/readline). Spring 앱이 먼저 실행 중이어야 한다.
 *
 * 사용: Claude Desktop 설정(claude_desktop_config.json)에서
 *   "command": "node", "args": ["<이 파일 절대경로>"]
 * 환경변수 MCP_HTTP_URL 로 대상 URL 변경 가능(기본 http://localhost:8080/mcp).
 */
const http = require("http");
const readline = require("readline");
const { URL } = require("url");

const TARGET = new URL(process.env.MCP_HTTP_URL || "http://localhost:8080/mcp");

// 로그는 stderr로만 (stdout은 순수 JSON-RPC 전용 — 오염 금지)
function logErr(...a) { process.stderr.write("[bridge] " + a.join(" ") + "\n"); }

function post(body) {
  return new Promise((resolve, reject) => {
    const data = Buffer.from(body, "utf8");
    const req = http.request(
      {
        hostname: TARGET.hostname,
        port: TARGET.port || 80,
        path: TARGET.pathname,
        method: "POST",
        headers: { "Content-Type": "application/json", "Content-Length": data.length },
      },
      (res) => {
        let chunks = "";
        res.on("data", (c) => (chunks += c));
        res.on("end", () => resolve({ status: res.statusCode, body: chunks }));
      }
    );
    req.on("error", reject);
    req.write(data);
    req.end();
  });
}

const rl = readline.createInterface({ input: process.stdin, terminal: false });

rl.on("line", async (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  let msg;
  try { msg = JSON.parse(trimmed); } catch { logErr("JSON 파싱 실패:", trimmed); return; }

  try {
    const res = await post(trimmed);
    // 알림(notification: id 없음)은 서버가 204/빈 응답 → stdout에 아무것도 쓰지 않음
    const isNotification = msg.id === undefined || msg.id === null;
    if (isNotification) return;
    if (res.body && res.body.trim()) {
      process.stdout.write(res.body.trim() + "\n");
    } else {
      // 본문 없는 응답인데 요청엔 id가 있으면 최소 응답 반환
      process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: msg.id, result: {} }) + "\n");
    }
  } catch (e) {
    logErr("HTTP 오류:", e.message);
    if (msg.id !== undefined && msg.id !== null) {
      process.stdout.write(JSON.stringify({
        jsonrpc: "2.0", id: msg.id,
        error: { code: -32000, message: "브리지→서버 연결 실패: " + e.message },
      }) + "\n");
    }
  }
});

rl.on("close", () => process.exit(0));
logErr("started → " + TARGET.href);
