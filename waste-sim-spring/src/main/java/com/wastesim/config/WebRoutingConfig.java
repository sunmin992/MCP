package com.wastesim.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 웹 UI 경로.
 *
 * <p>이 서버는 장량동 하나만 다루므로 화면도 하나다 — {@code localhost:8090/}가
 * {@code index.html}을 그대로 돌려준다. 스프링 부트가 루트의 정적 리소스를 이미
 * 서비스하므로 별도 포워딩이 필요 없고, 그래서 이 클래스에는 등록할 뷰가 없다.
 *
 * <p>라즈베리파이 엣지 도메인이 함께 있던 시절에는 {@code McpDomain.values()}를 돌며
 * {@code /waste}·{@code /edge}를 각각 {@code index.html}로 포워딩했다. 도메인이 하나가
 * 되면서 그 목록도, 슬러그 규약도 사라졌다. 클래스를 지우지 않고 남긴 이유는 뷰 라우팅을
 * 다시 붙일 때 자리가 여기라는 것을 표시해 두기 위해서다.
 */
@Configuration
public class WebRoutingConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 등록할 경로 없음 — 루트(/)는 정적 index.html이 직접 응답한다.
    }
}
