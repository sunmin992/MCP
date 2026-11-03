package com.wastesim.config;

import com.wastesim.region.OsmRegionResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Path;

/** 지역명을 공개 지도 블록으로 바꾸는 수집기. 꺼져 있으면 미등록 지역은 가상 블록이 된다. */
@Configuration
public class RegionResolverConfig {
    @Bean
    @ConditionalOnProperty(name = "region.resolver.enabled", havingValue = "true")
    OsmRegionResolver osmRegionResolver(@Value("${region.cache-dir}") String cacheDir,
            @Value("${region.nominatim-url}") String nominatim, @Value("${region.overpass-url}") String overpass,
            @Value("${region.osrm-url}") String osrm, @Value("${region.user-agent}") String userAgent) {
        // RegionCatalog 는 Spring 밖에서도 생성되므로 같은 보관 위치를 시스템 속성으로 알린다.
        System.setProperty("wastesim.region-cache", cacheDir);
        return new OsmRegionResolver(OsmRegionResolver.httpClient(userAgent), Path.of(cacheDir), nominatim, overpass, osrm);
    }
}
