package com.chatapp.userservice.security;

import com.chatapp.userservice.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Enforces "this request must carry a valid JWT" for whichever paths
 * WebMvcConfig registers this interceptor against (currently: everything
 * except /register, /login, /actuator/**  — see WebMvcConfig).
 *
 * This is a Spring MVC HandlerInterceptor, not a Spring Security filter — see
 * the explanation given alongside this change for the full reasoning. In
 * short: this project has exactly one binary auth rule (valid token or
 * reject) applied to a small, explicitly-listed set of endpoints, which is
 * squarely within what Spring MVC's own, much lighter interceptor mechanism
 * is built for, without pulling in Spring Security's filter chain /
 * SecurityContext / CSRF machinery to immediately turn most of it off again.
 *
 * preHandle runs before the target controller method. Returning false stops
 * the request right here — the controller method (and therefore
 * UserService/UserRepository/the database) is never reached for a rejected
 * request.
 */
@Component
public class JwtAuthenticationInterceptor implements HandlerInterceptor {

    // RFC 6750 defines this scheme name case-sensitively — "bearer" or
    // "BEARER" are not accepted, matching how every major HTTP API treats it.
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationInterceptor(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // CORS preflight bypass (build-order step 7, Angular frontend): a
        // browser's automatic OPTIONS preflight request (see WebMvcConfig's
        // addCorsMappings comment for what triggers one) NEVER carries an
        // Authorization header — that's not an oversight on the client's
        // part, browsers deliberately omit custom headers on preflight
        // requests, since the whole point of the preflight is asking
        // permission BEFORE sending the real request with its real headers.
        // Without this bypass, every preflight to a protected endpoint would
        // hit the "missing Authorization header" branch below and get 401'd
        // — and a failed preflight means the browser never sends the real
        // request at all, silently breaking the endpoint from Angular even
        // though a valid token would've been attached to the real call.
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            reject(response, "Missing or malformed Authorization header");
            return false;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        try {
            // Reuses JwtService's existing parseToken — the same signing key
            // and verification logic that generateAccessToken's counterpart
            // relies on, rather than re-implementing signature/expiry checks
            // here. Its return value (the claims) isn't used yet — nothing
            // downstream of this interceptor currently needs to know *who*
            // the caller is, only *that* they're a valid holder of a token.
            jwtService.parseToken(token);
        } catch (JwtException | IllegalArgumentException e) {
            // JwtException covers expired (ExpiredJwtException), malformed
            // (MalformedJwtException), and bad-signature
            // (io.jsonwebtoken.security.SignatureException) tokens — all its
            // subtypes. IllegalArgumentException covers a blank/empty token
            // string, which jjwt rejects before it even gets to signature
            // checking. All of these collapse to the same 401 here: the
            // caller only needs to know "this token isn't usable," not which
            // specific way it failed.
            reject(response, "Invalid or expired token");
            return false;
        }

        return true;
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(new ErrorResponse(message)));
    }
}
