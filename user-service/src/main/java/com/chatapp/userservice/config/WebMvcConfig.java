package com.chatapp.userservice.config;

import com.chatapp.userservice.security.JwtAuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires JwtAuthenticationInterceptor into the request pipeline.
 *
 * addPathPatterns("/**") + excludePathPatterns(...) — protect everything by
 * default, explicitly name the exceptions — rather than the other way around
 * (addPathPatterns naming only the specific endpoints that need protection).
 * This is a deliberate "secure by default" choice: with a deny-list, a new
 * endpoint added later is protected automatically unless someone explicitly
 * exempts it; with an allow-list, a new endpoint is silently PUBLIC by
 * default unless someone remembers to add it to the list. Forgetting to
 * exempt a genuinely-public endpoint fails loudly (it 401s, someone notices
 * immediately); forgetting to protect a genuinely-private one fails
 * silently (it just works, unauthenticated, until someone realizes). The
 * first failure mode is far safer than the second.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthenticationInterceptor jwtAuthenticationInterceptor;

    public WebMvcConfig(JwtAuthenticationInterceptor jwtAuthenticationInterceptor) {
        this.jwtAuthenticationInterceptor = jwtAuthenticationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthenticationInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/register", "/login", "/actuator/**");
    }
}
