package com.wastesim.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 웹 UI 경로.
 *
 * <p>이 서버의 화면은 <b>확인 화면 하나</b>다. {@code localhost:8090/}가 그리로 간다.
 *
 * <p>예전에는 {@code index.html}(챗 UI)이 루트를 받았다. 그 UI 는 통신 수단이 STOMP
 * 하나뿐이었는데 백엔드({@code ChatController})가 지워지면서 아무것도 동작하지 않게 됐고,
 * 그대로 두면 접속한 사람이 죽은 화면을 보고 서버가 고장난 줄 안다. 서브태스크 흐름은
 * MCP 도구로 옮겨 갔으므로 사람이 직접 쓰는 화면은 확인 하나로 충분하다.
 */
@Configuration
public class WebRoutingConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/confirm.html");
    }
}
