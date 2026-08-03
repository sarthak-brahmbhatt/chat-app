import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';

/**
 * A "functional" HTTP interceptor — a plain function, not a class — the
 * modern (Angular 15+) style, registered in app.config.ts via
 * `provideHttpClient(withInterceptors([authInterceptor]))`. Every outgoing
 * HttpClient request (from ANY service — AuthService, UserService) passes
 * through every registered interceptor first, in order, before actually
 * being sent.
 *
 * Its job: attach `Authorization: Bearer <token>` automatically, so
 * UserService (and anything else that calls a protected endpoint) never has
 * to remember to add that header itself — one place decides how
 * authentication gets attached to a request, matching the same "don't repeat
 * this per call site" reasoning as AuthService.login() storing the token in
 * one place. If there's no token yet (not logged in — e.g. the /register
 * and /login calls themselves), the request goes out unmodified; those
 * endpoints don't need it, and the backend doesn't require it for them.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const token = authService.accessToken();

  if (!token) {
    return next(req);
  }

  // HttpRequest objects are immutable — .clone() returns a NEW request with
  // the given changes rather than mutating the original, which is why this
  // is `req.clone(...)` and not `req.headers = ...`.
  const authorizedRequest = req.clone({
    setHeaders: { Authorization: `Bearer ${token}` },
  });
  return next(authorizedRequest);
};
