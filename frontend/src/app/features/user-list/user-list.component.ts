import { Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
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
   * back via `history.state` so its header can show a name instead of just
   * the raw userId, without a second network round trip to look it up again.
   */
  startChat(user: UserSummary): void {
    this.router.navigate(['/chat', user.id], {
      state: { recipientUsername: user.username },
    });
  }

  logout(): void {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
