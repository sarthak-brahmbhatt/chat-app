import { Component, OnInit, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { UserService } from '../../core/user.service';
import { UserSummary } from '../../models/user.models';

@Component({
  selector: 'app-user-list',
  templateUrl: './user-list.component.html',
})
export class UserListComponent implements OnInit {
  private readonly userService = inject(UserService);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  readonly users = signal<UserSummary[]>([]);
  readonly loading = signal(true);
  readonly errorMessage = signal<string | null>(null);

  /**
   * Which conversation is currently open in the shell's right panel, purely
   * to highlight that row in the sidebar (CLAUDE.md build-order step 17's
   * split view). UserListComponent isn't itself a routed component anymore
   * (it's rendered directly inside ChatShellComponent's template, not via
   * a `<router-outlet>`), so it can't read this off its own ActivatedRoute
   * — reading `router.url` on every NavigationEnd is the straightforward
   * alternative. `toSignal` with an explicit `initialValue` (rather than
   * leaving it `undefined` until the first event) means the very first
   * render already reflects whichever URL the app was already on.
   */
  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );

  isActive(user: UserSummary): boolean {
    return this.currentUrl() === `/chat/${user.id}`;
  }

  ngOnInit(): void {
    this.userService.getOtherUsers().subscribe({
      next: (users) => {
        this.users.set(users);
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('Failed to load users.');
        this.loading.set(false);
      },
    });
  }

  /**
   * Passes the user's display name along via router navigation state
   * (browser History API, populated by the Router) — ChatComponent reads it
   * back via `history.state` so its header can show a real name instead of
   * just the raw userId, without a second network round trip to look it up
   * again (UserSummary already has firstName/lastName on hand right here,
   * from the same GET /users response that rendered this list).
   * recipientUsername is passed too, as a fallback ChatComponent uses if
   * firstName/lastName are ever both blank.
   */
  startChat(user: UserSummary): void {
    this.router.navigate(['/chat', user.id], {
      state: {
        recipientFirstName: user.firstName,
        recipientLastName: user.lastName,
        recipientUsername: user.username,
      },
    });
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
