import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { USER_SERVICE_BASE_URL } from './api-config';
import { decodeJwtPayload } from './jwt-decode';
import { LoginRequest, LoginResponse, RegisterRequest, RegisterResponse } from '../models/auth.models';

/**
 * @Injectable({ providedIn: 'root' }) — Angular's dependency injection (DI)
 * in a nutshell: rather than every component that needs an AuthService
 * constructing one itself (`new AuthService()`), Angular maintains a
 * container ("injector") that creates ONE shared instance and hands it to
 * anything that asks for it. `providedIn: 'root'` registers this class with
 * the app's root injector, so the SAME instance is shared app-wide — which
 * matters a lot here specifically, since the in-memory token below needs to
 * be the one true copy, not a fresh, empty one per component.
 *
 * Where you'll see this instance get "asked for": either via constructor
 * injection (`constructor(private auth: AuthService) {}`) or, the more
 * modern style used throughout this app, the `inject()` function called at
 * field-initialization time (see below, and every other service/component in
 * this app) — same DI mechanism, just a plain function call instead of a
 * constructor parameter.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  // In-memory token storage — CLAUDE.md 3.3 documents the full reasoning
  // (avoids localStorage's XSS-readability and a cookie's cross-origin
  // complexity; the explicit tradeoff is that the token — and the logged-in
  // session — is lost on any page refresh, acceptable since there's no
  // refresh-token flow yet either).
  //
  // A signal, not a plain class field: a signal is a reactive container —
  // reading it (calling `accessToken()`) inside a component template or an
  // Angular `effect()` automatically subscribes that context to future
  // changes, so e.g. a "Logout" button can appear/disappear the instant
  // login()/logout() runs, without any manual event wiring. `.asReadonly()`
  // exposes a read-only view to the rest of the app — only THIS service is
  // allowed to call `.set(...)` on it.
  private readonly accessTokenSignal = signal<string | null>(null);
  readonly accessToken = this.accessTokenSignal.asReadonly();

  // A `computed` signal — its value is DERIVED from accessTokenSignal rather
  // than set directly, and automatically recalculates whenever
  // accessTokenSignal changes (e.g. becomes null again on logout()). Reads
  // our own user id back out of the token (see jwt-decode.ts's important
  // caveat: this is for display/filtering logic only, e.g. UserService
  // excluding ourselves from the user list — never for a security decision).
  readonly currentUserId = computed(() => {
    const token = this.accessTokenSignal();
    return token ? decodeJwtPayload(token).sub : null;
  });

  /**
   * Calls POST /register. Returns an Observable, not a Promise — RxJS's
   * core type, representing "a value (or error) that will arrive later, and
   * you subscribe to be notified." HttpClient methods always return one,
   * whether the response has 0, 1, or many values (HTTP responses always
   * complete after exactly one, but the TYPE is the same either way).
   * Nothing happens until something calls `.subscribe(...)` on this — an
   * Observable on its own is just a description of a pending operation, not
   * a promise already in flight. RegisterComponent is what subscribes.
   */
  register(request: RegisterRequest): Observable<RegisterResponse> {
    return this.http.post<RegisterResponse>(`${USER_SERVICE_BASE_URL}/register`, request);
  }

  /**
   * Calls POST /login and, on success, stores the returned access token.
   * `.pipe(tap(...))` runs a side effect (storing the token) on each
   * emitted value WITHOUT changing what gets emitted — the Observable
   * LoginComponent subscribes to still resolves to the LoginResponse itself,
   * `tap` just observes it in passing. This keeps "call the API" and "what
   * to do with a successful login" in one place, rather than making every
   * caller of login() remember to also store the token themselves.
   */
  login(request: LoginRequest): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>(`${USER_SERVICE_BASE_URL}/login`, request)
      .pipe(tap((response) => this.accessTokenSignal.set(response.accessToken)));
  }

  logout(): void {
    this.accessTokenSignal.set(null);
  }

  isLoggedIn(): boolean {
    return this.accessTokenSignal() !== null;
  }
}
