/**
 * Decodes a JWT's payload WITHOUT verifying its signature. That's fine here
 * specifically because this token just arrived directly from user-service's
 * own POST /login response over HTTPS-in-production/localhost-in-dev — we
 * already trust it as much as we trust the login call itself. This is NOT a
 * general-purpose "verify this token" utility, and must never be used to
 * make a security decision (e.g. "is this token valid" — that's exclusively
 * the backend's job, on every request, via JwtService/JwtValidator). The
 * only thing this is used for is reading our OWN user id back out of the
 * token we just received, purely for client-side display logic (see
 * UserService — filtering ourselves out of the user list).
 *
 * A JWT is three base64url segments joined by dots: header.payload.signature.
 * atob() decodes standard base64, but JWTs use base64URL (- and _ instead of
 * + and /), hence the character swap before decoding.
 */
export interface DecodedJwtClaims {
  sub: string;
  username: string;
  iat: number;
  exp: number;
}

export function decodeJwtPayload(token: string): DecodedJwtClaims {
  const payloadSegment = token.split('.')[1];
  const base64 = payloadSegment.replace(/-/g, '+').replace(/_/g, '/');
  return JSON.parse(atob(base64));
}
