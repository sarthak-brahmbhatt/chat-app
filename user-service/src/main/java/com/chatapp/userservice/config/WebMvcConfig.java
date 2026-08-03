package com.chatapp.userservice.config;

import com.chatapp.userservice.security.JwtAuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires JwtAuthenticationInterceptor into the request pipeline, and (build-order
 * step 7) configures CORS so the Angular dev server can call this API at all.
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

    /**
     * CORS (Cross-Origin Resource Sharing) is a BROWSER-enforced check, not a
     * server-side one — the backend has always been happy to answer requests
     * from anywhere (that's what Postman/curl/our own tests have been doing
     * all along, since they aren't browsers and don't apply this rule at
     * all). A real browser page is different: the Angular dev server runs at
     * http://localhost:4200, this API at http://localhost:8081 — different
     * origins (port counts as part of "origin"), so without this
     * configuration the browser refuses to hand the response back to
     * Angular's JavaScript, even though the server processed the request
     * successfully.
     *
     * Concretely, for OUR specific requests: Angular sends JSON bodies
     * (Content-Type: application/json) to /register and /login, and a custom
     * Authorization header to /users. Both of those push a request out of
     * the CORS spec's "simple request" category, which means the browser
     * sends a preflight — an automatic OPTIONS request asking "would you
     * accept the real request I'm about to send?" — BEFORE the real request
     * goes out at all. If that preflight doesn't get back the right
     * Access-Control-Allow-* headers, the browser never sends the real
     * request — this is exactly why CORS misconfiguration looks like "every
     * call from Angular fails," not just some of them.
     *
     * allowedOrigins is scoped to exactly the Angular dev server, not "*" —
     * this app isn't meant to be called from arbitrary origins, and "*"
     * combined with credentialed requests is rejected by browsers anyway
     * (moot here since we don't use allowCredentials — see AuthService's
     * in-memory token storage decision, CLAUDE.md 3.3).
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("http://localhost:4200")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
