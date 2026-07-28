package com.wastesim.config;

import com.wastesim.mcp.McpDomain;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 도메인별 웹 UI 경로를 {@code index.html} 하나로 포워딩한다.
 *
 * <pre>
 *   localhost:8090/        도메인 중립 시작화면 (index.html이 알아서 시작 패널을 띄움)
 *   localhost:8090/waste   장량동 수거 시뮬레이션 화면
 *   localhost:8090/edge    라즈베리파이 엣지 발열 화면
 * </pre>
 *
 * <p><b>왜 HTML 파일을 도메인마다 두지 않는가</b>: {@code index.html}은 1,100여 줄
 * 중 70%가 도메인과 무관한 공통 자산(스타일·WebSocket·메시지 렌더링·차트)이다.
 * 파일을 쪼개면 그 공통부가 복제되어 "채팅 버그 하나를 두 군데 고치는" 상태가 된다.
 * 그래서 <b>문서는 하나로 두고 사이드바만 갈아끼우되</b>, 주소는 도메인별로 갖게
 * 포워딩한다 — 새로고침·북마크·뒤로가기가 전부 정상 동작하면서 중복은 생기지 않는다.
 *
 * <p><b>포워딩 대상은 {@link McpDomain}에서 가져온다</b> — 도메인을 하나 추가할 때
 * 이 파일을 다시 편집하지 않아도 되도록. MCP 엔드포인트({@code /mcp/{slug}})와 웹
 * 경로({@code /{slug}})가 같은 목록에서 나오므로 둘이 어긋날 수 없다.
 */
@Configuration
public class WebRoutingConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (McpDomain domain : McpDomain.values()) {
            // forward: 이므로 리다이렉트가 아니다 — 브라우저 주소창에는 /edge가
            // 그대로 남고 서버만 index.html을 돌려준다. 클라이언트 JS가 이
            // 경로를 읽어 해당 도메인 사이드바를 띄운다.
            registry.addViewController("/" + domain.slug()).setViewName("forward:/index.html");
        }
    }
}
