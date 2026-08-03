import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { USER_SERVICE_BASE_URL } from './api-config';
import { AuthService } from './auth.service';
import { UserListResponse, UserSummary } from '../models/user.models';

/**
 * Fetches the "other users" list for UserListComponent's Start-Chat screen.
 *
 * Backend note: user-service's GET /users literally returns ALL users,
 * including yourself — this was a flagged, unresolved discrepancy against
 * CLAUDE.md section 2's "list all OTHER users" wording (see user-service
 * build-order step 4). This service is where that gets resolved: it filters
 * out the caller's own id client-side, using AuthService.currentUserId()
 * (decoded from our own JWT — see jwt-decode.ts).
 */
@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);

  /**
   * `.pipe(map(...))` transforms each emitted value — here, filtering the
   * raw UserListResponse's users array down to "everyone except me" and
   * returning just that array, so UserListComponent doesn't need to know
   * about the self-filtering step at all; it just gets the list it should
   * display. Unlike `tap` (used in AuthService.login for a side effect),
   * `map` changes what actually comes out the other end of the pipe.
   */
  getOtherUsers(): Observable<UserSummary[]> {
    return this.http.get<UserListResponse>(`${USER_SERVICE_BASE_URL}/users`).pipe(
      map((response) => {
        const myId = this.authService.currentUserId();
        return response.users.filter((user) => user.id.toString() !== myId);
      }),
    );
  }
}
