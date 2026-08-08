import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';
import { RegisterComponent } from './features/register/register.component';
import { LoginComponent } from './features/login/login.component';
import { ChatShellComponent } from './features/chat-shell/chat-shell.component';
import { ChatPlaceholderComponent } from './features/chat-shell/chat-placeholder.component';
import { ChatComponent } from './features/chat/chat.component';

/**
 * Standalone routing — each route points `component` directly at a
 * standalone component class (no NgModule-based lazy-loaded module to
 * declare it in, unlike pre-standalone Angular). `canActivate: [authGuard]`
 * on `chat` means the Router runs authGuard before allowing navigation
 * there — see auth.guard.ts. It's declared once on the PARENT route, not
 * repeated on each child — Angular runs a parent's guards before any child
 * route can activate, so a child-level `canActivate` here would be
 * redundant.
 *
 * `chat` is a parent route with two children (CLAUDE.md build-order step
 * 17): `''` renders ChatPlaceholderComponent ("select a conversation") and
 * `:userId` renders ChatComponent — both inside ChatShellComponent's
 * persistent sidebar layout. The URL for an open conversation is still
 * exactly `/chat/:userId`, unchanged from before this pass (a child route
 * under a `chat` parent composes to the identical path) — old bookmarks and
 * links keep working. The old standalone `/users` route now redirects here:
 * the sidebar's permanent presence inside the shell makes a separate
 * full-page user list redundant.
 */
export const routes: Routes = [
  { path: '', redirectTo: 'login', pathMatch: 'full' },
  { path: 'register', component: RegisterComponent },
  { path: 'login', component: LoginComponent },
  { path: 'users', redirectTo: 'chat' },
  {
    path: 'chat',
    component: ChatShellComponent,
    canActivate: [authGuard],
    children: [
      { path: '', component: ChatPlaceholderComponent },
      { path: ':userId', component: ChatComponent },
    ],
  },
  { path: '**', redirectTo: 'login' },
];
