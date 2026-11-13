package com.indie.studio.global.config;

import com.indie.studio.global.auth.PermissionInterceptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableScheduling
public class BeanConfig {

    /**
     * 관리자 화면이 다른 출처에 있을 때만 (app.cors.allowed-origins). SessionFilter 보다 앞에 두어 401 응답에도 CORS 헤더가 붙습니다.
     * 목록이 비어 있으면 아무 출처도 허용하지 않습니다 (같은 출처 전용).
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(StudioProperties props) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = props.cors() == null || props.cors().allowedOrigins() == null
                ? List.of() : props.cors().allowedOrigins().stream().map(String::trim).filter(o -> !o.isEmpty()).toList();
        if (!origins.isEmpty()) {
            CorsConfiguration cors = new CorsConfiguration();
            cors.setAllowedOrigins(origins);
            cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
            cors.setAllowedHeaders(List.of("Content-Type", PermissionInterceptor.CSRF_HEADER));
            cors.setAllowCredentials(true);   // 세션 쿠키
            cors.setMaxAge(600L);
            source.registerCorsConfiguration("/api/studio/**", cors);
        }
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder(StudioProperties props) {
        return new BCryptPasswordEncoder(props.login().bcryptStrength());
    }

    /** 무대 검증 작업 — GPU 서버는 한 번에 하나씩만 받습니다. 요청 스레드를 붙잡지 않으려고 따로 돌립니다 */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService gpuExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gpu-stage");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
