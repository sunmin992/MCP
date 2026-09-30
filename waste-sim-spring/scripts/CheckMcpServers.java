import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * SessionStart 훅: .mcp.json 의 MCP 서버가 떠 있는지 본다.
 *
 * <p>Claude Code 는 세션 시작 때 한 번만 MCP 에 연결하므로, 서버가 꺼져 있으면 그 세션 내내 도구가
 * 없다. 윈도우·맥 어디서나 돌도록 셸 대신 Java 단일 파일로 쓴다 — {@code java scripts/CheckMcpServers.java}.
 */
public class CheckMcpServers {

    private static final String[][] SERVERS = {
            {"waste-broker", "8089"},
            {"jangnyang-simulator", "8090"},
    };

    public static void main(String[] args) {
        List<String> down = new ArrayList<>();
        for (String[] s : SERVERS) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(s[1])), 1000);
            } catch (Exception e) {
                down.add(s[0] + "(:" + s[1] + ")");
            }
        }
        if (down.isEmpty()) {
            return;
        }

        String list = String.join(", ", down);
        String message = "MCP 서버가 꺼져 있습니다: " + list + ". 서버를 띄운 뒤 /mcp 로 다시 연결하세요.";
        String context = "세션 시작 시점에 MCP 서버 " + list + " 가 응답하지 않았다. 이 세션에서는 해당 MCP 도구가"
                + " 없을 수 있다. REST API 를 직접 부르기 전에 사용자에게 서버를 띄우고 /mcp 로 다시 연결하라고 먼저 알린다.";
        System.out.println("{\"systemMessage\":" + json(message)
                + ",\"hookSpecificOutput\":{\"hookEventName\":\"SessionStart\",\"additionalContext\":"
                + json(context) + "}}");
    }

    /** 콘솔 인코딩과 상관없이 한글이 깨지지 않게 ASCII 로만 내보낸다. */
    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7E) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
