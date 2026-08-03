import { Component, DestroyRef, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { filter } from 'rxjs';
import { ChatService } from '../../core/chat.service';

interface ChatBubble {
  messageId: string;
  direction: 'sent' | 'received';
  content: string;
  // Only meaningful for 'sent' bubbles — whether the single-tick ack has
  // arrived yet. Double tick (step 9) isn't handled here; out of scope.
  ticked: boolean;
}

@Component({
  selector: 'app-chat',
  templateUrl: './chat.component.html',
})
export class ChatComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly chatService = inject(ChatService);
  private readonly destroyRef = inject(DestroyRef);

  // The route param arrives as a string already — conveniently, this
  // matches the WebSocket protocol's string-id convention directly (see
  // chat.models.ts's note on the id-type quirk), no .toString() needed here.
  readonly recipientId = this.route.snapshot.paramMap.get('userId')!;
  readonly recipientLabel =
    (history.state as { recipientUsername?: string })?.recipientUsername ?? this.recipientId;

  readonly bubbles = signal<ChatBubble[]>([]);

  ngOnInit(): void {
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
    // open is out of scope for this pass.
    this.chatService.incomingMessages$
      .pipe(
        filter((message) => message.senderId === this.recipientId),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((message) => {
        this.bubbles.update((current) => [
          ...current,
          { messageId: message.messageId, direction: 'received', content: message.content, ticked: false },
        ]);
      });

    this.chatService.tickAcks$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((ack) => {
      this.bubbles.update((current) =>
        current.map((bubble) => (bubble.messageId === ack.messageId ? { ...bubble, ticked: true } : bubble)),
      );
    });
  }

  send(rawContent: string): void {
    const content = rawContent.trim();
    if (!content) {
      return;
    }

    const messageId = this.chatService.sendMessage(this.recipientId, content);
    this.bubbles.update((current) => [...current, { messageId, direction: 'sent', content, ticked: false }]);
  }

  ngOnDestroy(): void {
    this.chatService.disconnect();
  }
}
