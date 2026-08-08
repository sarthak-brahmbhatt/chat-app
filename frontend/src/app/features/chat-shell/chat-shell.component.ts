import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ChatService } from '../../core/chat.service';
import { UserListComponent } from '../user-list/user-list.component';

/**
 * The WhatsApp-style split view: a persistent left sidebar (UserListComponent,
 * reused as-is) plus a right panel whose content is whichever child route is
 * active — ChatPlaceholderComponent at the bare `/chat` path ("select a
 * conversation"), ChatComponent at `/chat/:userId`. Clicking a different user
 * in the sidebar navigates the CHILD route only; this shell component itself
 * is never destroyed/recreated by that, which is exactly what keeps the
 * sidebar on screen instead of the old full-page navigation away from it.
 *
 * See CLAUDE.md build-order step 17 for why this is a parent route + child
 * outlet rather than one component owning both panels' state directly, and
 * for the real Angular route-reuse gotcha this design surfaces in
 * ChatComponent (switching the child route param does NOT recreate the
 * component instance, so ChatComponent reacts to paramMap changes rather
 * than reading the route snapshot once).
 *
 * Owns the WebSocket connection's lifecycle now, not ChatComponent — this
 * shell is what actually spans "however long the user is browsing chats,"
 * connecting once on entry and disconnecting only when leaving /chat
 * entirely (e.g. logout). Before the split view, ChatComponent connecting/
 * disconnecting in its own ngOnInit/ngOnDestroy was correct because opening
 * ANY chat and leaving ANY chat were the same event as mounting/unmounting
 * the (only) chat-related component. That's no longer true once switching
 * between two open chats reuses the same ChatComponent instance without
 * ever destroying it (see this class's own comment above) — connect/
 * disconnect belongs at the level that actually matches "in the chat
 * experience at all," which is this shell, not any individual chat.
 */
@Component({
  selector: 'app-chat-shell',
  templateUrl: './chat-shell.component.html',
  imports: [RouterOutlet, UserListComponent],
})
export class ChatShellComponent implements OnInit, OnDestroy {
  private readonly chatService = inject(ChatService);

  ngOnInit(): void {
    this.chatService.connect();
  }

  ngOnDestroy(): void {
    this.chatService.disconnect();
  }
}
