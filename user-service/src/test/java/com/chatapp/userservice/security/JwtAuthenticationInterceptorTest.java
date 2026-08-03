package com.chatapp.userservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.SecretKey;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A plain Mockito unit test for JwtAuthenticationInterceptor's preHandle
 * logic directly — HttpServletRequest/Response are mocked (no real HTTP,
 * no Spring context), but JwtService is real, same reasoning as
 * AuthServiceTest: this needs to exercise genuine token validation (a truly
 * expired token, a truly bad signature), not a mocked stand-in that just
 * returns whatever a test tells it to.
 *
 * Complements, rather than duplicates, UserControllerTest's GET /users
 * tests: this class checks the interceptor's own decision logic in
 * isolation; UserControllerTest checks that the interceptor is actually
 * wired into the request pipeline in front of that specific endpoint.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationInterceptorTest {

    private static final String SECRET = "test-only-jwt-signing-secret-at-least-32-bytes-long-xyz";

    private JwtService jwtService;
    private JwtAuthenticationInterceptor interceptor;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = new JwtService(SECRET, 15);
        interceptor = new JwtAuthenticationInterceptor(jwtService, new ObjectMapper());

        responseBody = new StringWriter();
        // Not every test triggers a rejection (the valid-token test never
        // touches the response writer), so this stub is "lenient" — it's
        // fine if a given test never calls getWriter() at all.
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    @Test
    void preHandle_withValidToken_allowsRequestThrough() throws Exception {
        String token = jwtService.generateAccessToken(1L, "alice");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void preHandle_withMissingAuthorizationHeader_returns401AndBlocksRequest() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(responseBody.toString()).contains("Missing or malformed Authorization header");
    }

    @Test
    void preHandle_withHeaderMissingBearerPrefix_returns401() throws Exception {
        // A token-shaped value is present, but not prefixed "Bearer " — e.g.
        // a client that forgot the scheme, or used a different one.
        when(request.getHeader("Authorization")).thenReturn("some-token-without-bearer-prefix");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(responseBody.toString()).contains("Missing or malformed Authorization header");
    }

    @Test
    void preHandle_withExpiredToken_returns401() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minusSeconds(3600);
        // Built directly with jjwt (not via JwtService, which has no way to
        // mint an already-expired token) using the SAME key JwtService signs
        // with, so this is a genuinely validly-signed-but-expired token —
        // exactly the case ExpiredJwtException exists for.
        String expiredToken = Jwts.builder()
                .subject("1")
                .claim("username", "alice")
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(key)
                .compact();
        when(request.getHeader("Authorization")).thenReturn("Bearer " + expiredToken);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(responseBody.toString()).contains("Invalid or expired token");
    }

    @Test
    void preHandle_withTamperedSignature_returns401() throws Exception {
        // Signed with a DIFFERENT key than the one JwtService verifies
        // against — simulates a forged/tampered token. jjwt should reject
        // this with a SignatureException (a JwtException subtype).
        SecretKey wrongKey = Keys.hmacShaKeyFor("a-completely-different-signing-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8));
        String tokenSignedWithWrongKey = Jwts.builder()
                .subject("1")
                .claim("username", "alice")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(900)))
                .signWith(wrongKey)
                .compact();
        when(request.getHeader("Authorization")).thenReturn("Bearer " + tokenSignedWithWrongKey);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(responseBody.toString()).contains("Invalid or expired token");
    }
}
