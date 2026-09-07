import { Component, OnInit, computed, inject, signal } from '@angular/core';
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

  /**
   * The two assistants, pinned above everyone else and always in the same
   * order: the prompt-stuffing bot first, the tool-calling bot second.
   *
   * Ordered explicitly rather than by id or name, because the order IS the
   * story — they are Version 1 and Version 2 of the same thing, and a demo
   * that compares them wants them adjacent and in that sequence every time.
   */
  readonly bots = computed(() => {
    const byType = (type: string) => this.users().filter((user) => user.userType === type);
    return [...byType('BOT'), ...byType('BOT_TOOL')];
  });

  /** Everyone else, in the order the API returned them. */
  readonly people = computed(() => this.users().filter((user) => user.userType === 'USER'));

  /**
   * A short label for a bot's approach, shown under its name.
   *
   * Says what makes the two different in three words, so the sidebar itself
   * carries the comparison rather than relying on someone remembering which
   * assistant is which mid-conversation.
   */
  botSubtitle(user: UserSummary): string {
    return user.userType === 'BOT_TOOL' ? 'Tool calling' : 'Prompt stuffing';
  }

  /** Drives the accent colour; see the .bot-1 / .bot-2 rules in styles.css. */
  botAccentClass(user: UserSummary): string {
    return user.userType === 'BOT_TOOL' ? 'bot-2' : 'bot-1';
  }

  /** Initials for the avatar disc — "Dr" style, at most two letters. */
  initialsOf(user: UserSummary): string {
    const first = user.firstName?.trim()?.[0] ?? '';
    const last = user.lastName?.trim()?.[0] ?? '';
    return (first + last).toUpperCase() || user.username.slice(0, 2).toUpperCase();
  }
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
        // Forwarded so the chat header can carry the same accent colour as the
        // sidebar entry — with two assistants answering, the open conversation
        // should say at a glance which one it is.
        recipientUserType: user.userType,
      },
    });
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
