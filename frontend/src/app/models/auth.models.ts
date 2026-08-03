/**
 * TypeScript interfaces mirroring user-service's register/login DTOs
 * (see user-service/src/main/java/.../dto/). Interfaces here have no runtime
 * existence — they're erased at compile time — they just give the compiler
 * (and you, reading this) a name for "the shape of JSON going over HTTP,"
 * checked against how AuthService actually uses it.
 */

export interface RegisterRequest {
  username: string;
  password: string;
  firstName: string;
  lastName: string;
}

// Matches RegisterResponse.java: note `id` is a NUMBER here — Java's `Long`
// serializes as a plain JSON number, not a string.
export interface RegisterResponse {
  id: number;
  username: string;
  firstName: string;
  lastName: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
}

// Matches ErrorResponse.java — the one shape every 400/401/409 body uses.
export interface ApiErrorResponse {
  message: string;
}
