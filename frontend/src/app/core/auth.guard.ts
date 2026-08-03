import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * A "functional" route guard (the modern style, matching the functional
 * interceptor in auth.interceptor.ts) — plugged into a route's
 * `canActivate` array (see app.routes.ts). The Router calls this before
 * navigating to a matching route; returning `true` allows the navigation,
 * returning a UrlTree (via router.createUrlTree/router.parseUrl) redirects
 * instead.
 *
 * Minimal on purpose for this pass: it only checks "is there a token in
 * memory right now," not whether that token has expired server-side — the
 * backend is still the real authority on validity for every actual request
 * (JwtAuthenticationInterceptor on /users, ChatWebSocketHandler's auth
 * message on the WebSocket). This guard just prevents the obviously-broken
 * UX of landing on /users or /chat with no token at all and watching every
 * request silently 401.
 */
export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (authService.isLoggedIn()) {
    return true;
  }

  return router.parseUrl('/login');
};
