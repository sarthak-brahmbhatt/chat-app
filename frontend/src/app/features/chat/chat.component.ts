import { Component, DestroyRef, ElementRef, OnDestroy, OnInit, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { ChatService } from '../../core/chat.service';
import { ConversationMessageResponse, IncomingChatMessage } from '../../models/chat.models';

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
  // ISO-8601 string. For a HISTORICAL bubble (toBubble) this is the real,
  // server-persisted sentAt. For a LIVE-RECEIVED bubble it's the sentAt the
  // server shared between the Kafka-persisted row and this live envelope
  // (see chat.models.ts's IncomingChatMessage comment). For the SENDER'S OWN
  // optimistic bubble (send(), rendered before any server round trip) it's
  // just the local clock at click-time — cosmetic only; if this conversation
  // is ever reloaded, the history fetch replaces it with the authoritative
  // server value, same as tickState already works for that bubble.
  sentAt: string;
}

/**
 * Reused essentially unchanged from before the split view (CLAUDE.md
 * build-order step 17), with one structural change that pass required:
 * this component's instance can now be REUSED across a sidebar switch from
 * one open chat to another (Angular's default RouteReuseStrategy reuses a
 * routed component when only the URL param changes on the same route
 * config), so `ngOnInit` only ever runs ONCE per instance, not once per
 * conversation opened. Everything that used to happen once in ngOnInit off
 * a snapshot read of the route param now happens reactively off
 * `route.paramMap`, in `switchToConversation` below, guarded by a
 * generation counter so a slow, now-stale response for a since-abandoned
 * switch can't clobber the newly selected conversation's state.
 */
@Component({
  selector: 'app-chat',
  templateUrl: './chat.component.html',
  imports: [RouterLink],
})
export class ChatComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly chatService = inject(ChatService);
  private readonly authService = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly messagesEl = viewChild<ElementRef<HTMLDivElement>>('messagesEl');

  readonly recipientId = signal('');
  readonly recipientLabel = signal('');
  readonly bubbles = signal<ChatBubble[]>([]);
  readonly hasMoreHistory = signal(true);
  readonly loadingOlder = signal(false);

  // Gates whether a live incoming_message gets appended to `bubbles` right
  // now — see this class's own comment further down (in switchToConversation)
  // for the narrow, accepted race this exists to avoid.
  private historyLoaded = false;

  // Incremented on every conversation switch; a history response only gets
  // applied if it's still the CURRENT switch's response when it resolves.
  // Needed because the WebSocket connection (and therefore this component's
  // event subscriptions) is now persistent across switches, owned by
  // ChatShellComponent — see that class's comment for why that changed.
  private historySwitchGeneration = 0;

  ngOnInit(): void {
    // Set up ONCE, not per-switch: the connection itself is owned and kept
    // open by ChatShellComponent for as long as the user is anywhere under
    // /chat, so these streams keep emitting across every sidebar click —
    // only WHICH conversation cares about a given event changes, handled
    // inside each callback below by checking against the CURRENT
    // recipientId/historyLoaded state at delivery time, not at subscribe
    // time.
    this.chatService.incomingMessages$
      .pipe(
        filter((message) => message.senderId === this.recipientId()),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((message) => this.handleIncomingMessage(message));

    // A TickAck's own `tick` field is already exactly 'single' | 'double' —
    // the same union ChatBubble.tickState uses — so it can be assigned
    // straight across without any translation. Safe regardless of which
    // conversation is currently open: a tick for a message not in the
    // CURRENT `bubbles` array simply matches nothing and is a no-op.
    this.chatService.tickAcks$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((ack) => {
      this.bubbles.update((current) =>
        current.map((bubble) => (bubble.messageId === ack.messageId ? { ...bubble, tickState: ack.tick } : bubble)),
      );
    });

    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const userId = params.get('userId')!;
      this.switchToConversation(userId);
    });
  }

  private switchToConversation(userId: string): void {
    const generation = ++this.historySwitchGeneration;

    this.recipientId.set(userId);
    this.recipientLabel.set(this.resolveRecipientLabel(userId));
    this.bubbles.set([]);
    this.hasMoreHistory.set(true);
    this.historyLoaded = false;

    /**
     * History is fetched and fully applied to `bubbles` BEFORE this
     * conversation is treated as "live" (historyLoaded = true) — mirrors
     * the exact same "no gap, no duplicates" reasoning this component
     * always used, just re-anchored to a per-SWITCH boundary now that the
     * socket itself no longer opens/closes per conversation (Chat­Shell­Component
     * owns it for the whole session). The one thing that changed: because
     * the socket is already open and continuously receiving events for
     * EVERY conversation (not just this one), a live message for THIS
     * conversation could in principle arrive during this fetch's round
     * trip. Rather than buffer it, `handleIncomingMessage` simply drops a
     * live append that arrives before `historyLoaded` flips true — a
     * narrow, accepted, self-healing gap (the message is already durably
     * persisted via Kafka regardless; the next time this conversation is
     * opened, history shows it correctly) rather than new per-conversation
     * buffering machinery for a sub-second window.
     */
    this.chatService.getHistory(userId).subscribe({
      next: (history) => {
        if (generation !== this.historySwitchGeneration) {
          return; // a newer switch has already superseded this response
        }
        const myUserId = this.authService.currentUserId();
        this.bubbles.set(history.messages.map((message) => this.toBubble(message, myUserId)));
        this.hasMoreHistory.set(history.hasMore);
        this.historyLoaded = true;
        this.scrollToBottomNextFrame();
      },
      // Degrade gracefully rather than leaving the chat entirely unusable:
      // if fetching history fails (e.g. a transient network error), the
      // conversation still opens - just without past context.
      error: () => {
        if (generation !== this.historySwitchGeneration) {
          return;
        }
        this.historyLoaded = true;
      },
    });
  }

  /**
   * Prefers the full name UserListComponent forwarded via router state
   * (see its startChat() comment) over the bare username, over the raw id
   * — in that order. history.state reflects the navigation that's CURRENTLY
   * active, so re-reading it on every switch (not just once) correctly
   * picks up the newly clicked user's name each time; it's empty on a hard
   * refresh (not persisted across a real page reload), which is exactly
   * when this needs to fall all the way back to the id - there's no second
   * lookup call to re-fetch the name in that case.
   */
  private resolveRecipientLabel(userId: string): string {
    const state = history.state as {
      recipientFirstName?: string;
      recipientLastName?: string;
      recipientUsername?: string;
    };
    const fullName = [state?.recipientFirstName, state?.recipientLastName].filter(Boolean).join(' ');
    return fullName || state?.recipientUsername || userId;
  }

  private handleIncomingMessage(message: IncomingChatMessage): void {
    if (!this.historyLoaded) {
      // See switchToConversation's comment: an accepted, narrow, self-healing
      // gap, not a silent bug.
      return;
    }
    this.bubbles.update((current) => [
      ...current,
      { messageId: message.messageId, direction: 'received', content: message.content, tickState: 'pending', sentAt: message.sentAt },
    ]);
    this.scrollToBottomIfAlreadyNearIt();
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
      sentAt: message.sentAt,
    };
  }

  send(rawContent: string): void {
    const content = rawContent.trim();
    if (!content) {
      return;
    }

    const messageId = this.chatService.sendMessage(this.recipientId(), content);
    // sentAt here is the local clock, not a server value — see this bubble's
    // shape comment (ChatBubble.sentAt) for why that's fine: purely cosmetic
    // until/unless this conversation is reopened, at which point the history
    // fetch replaces it with the real persisted timestamp.
    this.bubbles.update((current) => [
      ...current,
      { messageId, direction: 'sent', content, tickState: 'pending', sentAt: new Date().toISOString() },
    ]);
    this.scrollToBottomNextFrame();
  }

  /**
   * WhatsApp-style: time only for a message sent today (by the VIEWER's
   * local calendar day, not UTC — `new Date(iso)` already converts to local
   * time zone fields, so `getFullYear()/getMonth()/getDate()` below need no
   * manual timezone math), date + time for anything older. Used directly in
   * the template rather than a pipe — this is the only place in the app
   * that needs this formatting, so a pipe would be structure for a second
   * use case that doesn't exist yet.
   */
  formatTimestamp(sentAt: string): string {
    const date = new Date(sentAt);
    const now = new Date();
    const isToday =
      date.getFullYear() === now.getFullYear() &&
      date.getMonth() === now.getMonth() &&
      date.getDate() === now.getDate();

    const time = date.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
    if (isToday) {
      return time;
    }
    const day = date.toLocaleDateString([], { month: 'short', day: 'numeric' });
    return `${day}, ${time}`;
  }

  /**
   * Fires when the message list is scrolled — only actually triggers a
   * fetch once the user is within a small threshold of the top, and only
   * one page at a time (`loadingOlder` guard). Deliberately does NOT try to
   * avoid re-triggering immediately after the scroll-position restore in
   * `loadOlderMessages` completes: the restored scrollTop lands well above
   * this threshold in practice (it grows by the height of the whole
   * prepended page, typically hundreds of pixels), so a real runaway loop
   * doesn't occur — see loadOlderMessages for the restore math.
   */
  onScroll(): void {
    const el = this.messagesEl()?.nativeElement;
    if (!el || el.scrollTop > 60 || this.loadingOlder() || !this.hasMoreHistory() || !this.historyLoaded) {
      return;
    }
    this.loadOlderMessages();
  }

  private loadOlderMessages(): void {
    const el = this.messagesEl()?.nativeElement;
    const oldest = this.bubbles()[0];
    if (!el || !oldest) {
      return;
    }

    const recipientId = this.recipientId();
    this.loadingOlder.set(true);

    // Captured BEFORE the network round trip, not after - the whole point
    // is to know how tall the list was right before older messages get
    // prepended, so the restore math below can compute exactly how much
    // taller it got.
    const previousScrollHeight = el.scrollHeight;
    const previousScrollTop = el.scrollTop;

    this.chatService.getHistory(recipientId, oldest.sentAt).subscribe({
      next: (history) => {
        this.loadingOlder.set(false);
        if (recipientId !== this.recipientId()) {
          return; // the user switched conversations while this was in flight
        }
        const myUserId = this.authService.currentUserId();
        const older = history.messages.map((message) => this.toBubble(message, myUserId));
        this.bubbles.update((current) => [...older, ...current]);
        this.hasMoreHistory.set(history.hasMore);

        /**
         * Scroll-position preservation: prepending older messages ABOVE the
         * content already on screen makes the container taller without
         * moving anything the user was already looking at — but leaving
         * `scrollTop` untouched would keep the same PIXEL offset from the
         * top, which is now a completely different, wrong position (it'd
         * visually yank the view down to show the newly prepended content
         * instead of what the user was reading). The fix is the standard
         * one: once the browser has actually laid out and painted the new
         * bubbles (`requestAnimationFrame` runs after layout/paint — a
         * plain post-signal-update callback or `setTimeout(0)` can both
         * fire before `scrollHeight` has caught up to the new content),
         * add exactly how much taller the content got
         * (`newScrollHeight - oldScrollHeight`) to the old scrollTop. That
         * keeps the same pixels in view, so nothing visually jumps.
         */
        requestAnimationFrame(() => {
          el.scrollTop = el.scrollHeight - previousScrollHeight + previousScrollTop;
        });
      },
      error: () => this.loadingOlder.set(false),
    });
  }

  private scrollToBottomNextFrame(): void {
    const el = this.messagesEl()?.nativeElement;
    if (!el) {
      return;
    }
    requestAnimationFrame(() => {
      el.scrollTop = el.scrollHeight;
    });
  }

  /**
   * For a LIVE incoming message only: stick to the bottom if the user was
   * already near it (they're actively watching the conversation), but don't
   * yank someone who's scrolled up reading older history down to the newest
   * message the moment one arrives elsewhere. A generous threshold (200px)
   * since "near the bottom" should feel forgiving, not pixel-exact.
   */
  private scrollToBottomIfAlreadyNearIt(): void {
    const el = this.messagesEl()?.nativeElement;
    if (!el) {
      return;
    }
    const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    if (distanceFromBottom < 200) {
      this.scrollToBottomNextFrame();
    }
  }

  ngOnDestroy(): void {
    // Connection lifecycle now belongs to ChatShellComponent, not this
    // component - see that class's comment for why. Nothing to clean up
    // here beyond takeUntilDestroyed's automatic unsubscription above.
  }
}
