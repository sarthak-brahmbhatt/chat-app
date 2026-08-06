import { Component, DestroyRef, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { ChatService } from '../../core/chat.service';
import { ConversationMessageResponse } from '../../models/chat.models';

interface ChatBubble {
  messageId: string;
  direction: 'sent' | 'received';
  content: string;
  // Only meaningful for 'sent' bubbles: 'pending' until the server's
  // single-tick ack arrives, 'single' until the recipient's delivered_ack
  // round-trips back as a double-tick ack (build-order step 9), 'double'
  // once it does. 'received' bubbles just carry 'pending' unused — the
  // template only ever reads this for 'sent' bubbles.
  tickState: 'pending' | 'single' | 'double';
}

@Component({
  selector: 'app-chat',
  templateUrl: './chat.component.html',
})
export class ChatComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly chatService = inject(ChatService);
  private readonly authService = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  // The route param arrives as a string already — conveniently, this
  // matches the WebSocket protocol's string-id convention directly (see
  // chat.models.ts's note on the id-type quirk), no .toString() needed here.
  readonly recipientId = this.route.snapshot.paramMap.get('userId')!;
  readonly recipientLabel =
    (history.state as { recipientUsername?: string })?.recipientUsername ?? this.recipientId;

  readonly bubbles = signal<ChatBubble[]>([]);

  /**
   * History is fetched and fully applied to `bubbles` BEFORE connect() is
   * ever called — that ordering (not any de-duplication logic afterward)
   * is what makes the history-to-live transition clean:
   *
   *   - NO GAP: if this were reversed (connect first, fetch history
   *     second), a live message could arrive and get appended to
   *     `bubbles` in the window before the history fetch resolves — then
   *     seeding `bubbles` from the history response would silently
   *     overwrite it. Fetching first means `bubbles` already holds full
   *     history by the time anything live can possibly arrive and append
   *     to it.
   *   - NO DUPLICATES: a message can only ever appear in the history
   *     response OR arrive live, never both. Live delivery
   *     (deliverIfRecipientConnected in ChatWebSocketHandler.java) is a
   *     one-time check against who's connected AT THE MOMENT a message is
   *     sent — there's no replay/redelivery mechanism for a message
   *     that's already been delivered and persisted (CLAUDE.md explicitly
   *     has no offline-message-queue). So a message the history snapshot
   *     already includes can never ALSO arrive over the live socket
   *     later; the two sources are disjoint by construction, not by
   *     anything this component has to check for.
   */
  ngOnInit(): void {
    const myUserId = this.authService.currentUserId();

    this.chatService.getHistory(this.recipientId).subscribe({
      next: (history) => {
        this.bubbles.set(history.messages.map((message) => this.toBubble(message, myUserId)));
        this.connectAndSubscribeToLiveEvents();
      },
      // Degrade gracefully rather than leaving the chat entirely unusable:
      // if fetching history fails (e.g. a transient network error), the
      // conversation still opens - just without past context - instead of
      // a failed history load blocking live chat from working at all.
      error: () => this.connectAndSubscribeToLiveEvents(),
    });
  }

  private toBubble(message: ConversationMessageResponse, myUserId: string | null): ChatBubble {
    // A persisted message was, by definition, already single-ticked before
    // it could ever reach messagedb (handleChatMessage sends the single
    // tick BEFORE publishing to Kafka - CLAUDE.md 3.4) - so a historical
    // 'sent' bubble is never 'pending', only 'single' or 'double' depending
    // on the persisted `delivered` flag.
    const tickState = message.delivered ? 'double' : 'single';
    return {
      messageId: message.messageId,
      direction: message.senderId === myUserId ? 'sent' : 'received',
      content: message.content,
      tickState,
    };
  }

  private connectAndSubscribeToLiveEvents(): void {
    this.chatService.connect();

    // takeUntilDestroyed(this.destroyRef): automatically unsubscribes when
    // this component is destroyed (e.g. navigating away). Without it, these
    // subscriptions to ChatService's (app-wide singleton) streams would
    // outlive this component — harmless-looking now, but the next time you
    // open a chat, a SECOND set of subscriptions would stack on top of any
    // leaked ones, double-handling every future message.
    //
    // This ChatComponent only represents a conversation with ONE specific
    // person (recipientId) — filtering on message.senderId === recipientId
    // means a message arriving from some OTHER user (a different
    // conversation entirely) is deliberately ignored here. A real inbox
    // that surfaces messages from anyone regardless of which chat window is
    // open is out of scope for this pass. Sending the delivered_ack that
    // eventually produces the sender's double tick happens automatically
    // inside ChatService itself (see its 'message' listener) — nothing
    // needed here for that part.
    this.chatService.incomingMessages$
      .pipe(
        filter((message) => message.senderId === this.recipientId),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((message) => {
        this.bubbles.update((current) => [
          ...current,
          { messageId: message.messageId, direction: 'received', content: message.content, tickState: 'pending' },
        ]);
      });

    // A TickAck's own `tick` field is already exactly 'single' | 'double' —
    // the same union ChatBubble.tickState uses — so it can be assigned
    // straight across without any translation.
    this.chatService.tickAcks$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((ack) => {
      this.bubbles.update((current) =>
        current.map((bubble) => (bubble.messageId === ack.messageId ? { ...bubble, tickState: ack.tick } : bubble)),
      );
    });
  }

  send(rawContent: string): void {
    const content = rawContent.trim();
    if (!content) {
      return;
    }

    const messageId = this.chatService.sendMessage(this.recipientId, content);
    this.bubbles.update((current) => [...current, { messageId, direction: 'sent', content, tickState: 'pending' }]);
  }

  ngOnDestroy(): void {
    this.chatService.disconnect();
  }
}
