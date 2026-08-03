import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';

/**
 * The app's dependency-injection root config — every provider listed here
 * becomes available for injection (via constructor injection or `inject()`)
 * anywhere in the app. provideHttpClient sets up Angular's HttpClient
 * service (used by AuthService/UserService for HTTP calls);
 * withInterceptors registers authInterceptor to run on every request it
 * handles. provideRouter wires up the routes defined in app.routes.ts.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
  ],
};
