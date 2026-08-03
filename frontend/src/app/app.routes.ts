import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';
import { RegisterComponent } from './features/register/register.component';
import { LoginComponent } from './features/login/login.component';
import { UserListComponent } from './features/user-list/user-list.component';
import { ChatComponent } from './features/chat/chat.component';

/**
 * Standalone routing — each route points `component` directly at a
 * standalone component class (no NgModule-based lazy-loaded module to
 * declare it in, unlike pre-standalone Angular). `canActivate: [authGuard]`
 * on /users and /chat/:userId means the Router runs authGuard before
 * allowing navigation there — see auth.guard.ts.
 */
export const routes: Routes = [
  { path: '', redirectTo: 'login', pathMatch: 'full' },
  { path: 'register', component: RegisterComponent },
  { path: 'login', component: LoginComponent },
  { path: 'users', component: UserListComponent, canActivate: [authGuard] },
  { path: 'chat/:userId', component: ChatComponent, canActivate: [authGuard] },
  { path: '**', redirectTo: 'login' },
];
