import { Injectable, inject, signal } from '@angular/core';
import { Observable, Subject, filter } from 'rxjs';
import { CHAT_SERVICE_WS_URL } from './api-config';
import { AuthService } from './auth.service';
import { ChatMessageRequest, DeliveredAck, IncomingChatMessage, ServerChatEvent, TickAck } from '../models/chat.models';

/**
 * Owns one WebSocket connection to chat-service and turns its raw messages
 * into RxJS streams components can subscribe to.
 *
 * Why RxJS Observables/Subjects fit this naturally, if they're new to you:
 * a WebSocket delivers an unpredictable NUMBER of messages over time (zero,
 * one, or thousands, whenever the server feels like sending them) — that's
 * exactly what an Observable models (a stream of values arriving over time),
 * unlike a Promise (models exactly one value, once). A `Subject` is an
 * Observable you can manually push values INTO from imperative code (here:
 * the WebSocket's own 'message' event handler, which isn't itself
 * RxJS-aware) — it's the bridge between a plain browser API (WebSocket) and
 * RxJS's world. Components never touch the Subject directly, only the
 * `Observable` view of it (via `.asObservable()`/the readonly fields below)
 * — only THIS service is allowed to push new events into the stream.
 *
 * Connection lifecycle is scoped to whoever uses this service (ChatComponent
 * calls connect() on init, disconnect() on destroy) — not a single
 * app-wide-always-on connection kept alive across every screen. Simpler,
 * and matches this pass's scope: proving one chat session's send/receive/
 * tick wiring works, not building a persistent background-notification
 * system for the whole app.
 */
@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly authService = inject(AuthService);
  private socket: WebSocket | null = null;

  private readonly eventsSubject = new Subject<ServerChatEvent>();

  // .pipe(filter(...)) narrows the combined event stream down to just one
  // variant each — the `(event): event is TickAck => ...` syntax is a
  // TypeScript type predicate, which is what lets TypeScript know that
  // anything coming out of tickAcks$ is specifically a TickAck, not the
  // wider ServerChatEvent union, without an explicit cast.
  readonly incomingMessages$: Observable<IncomingChatMessage> = this.eventsSubject.pipe(
    filter((event): event is IncomingChatMessage => event.type === 'incoming_message'),
  );
  readonly tickAcks$: Observable<TickAck> = this.eventsSubject.pipe(
    filter((event): event is TickAck => event.type === 'ack'),
  );

  // 'authFailed' surfaces the one real signal the protocol gives us when the
  // auth message is rejected: the server closes the socket with code 1008
  // (see ChatWebSocketHandler.java). There's no positive "auth succeeded"
  // ack in this protocol (a deliberate build-order step 5 choice) — so
  // ChatComponent can only ever learn about a FAILED auth explicitly; a
  // successful one is inferred by the connection simply staying open.
  readonly connectionState = signal<'connecting' | 'open' | 'closed'>('closed');
  readonly authFailed = signal(false);

  connect(): void {
    if (this.socket) {
      return;
    }

    const token = this.authService.accessToken();
    if (!token) {
      throw new Error('Cannot open a chat connection while logged out');
    }

    this.authFailed.set(false);
    this.connectionState.set('connecting');
    this.socket = new WebSocket(CHAT_SERVICE_WS_URL);

    this.socket.addEventListener('open', () => {
      // The first message on the connection is always the raw JWT string —
      // no JSON envelope — per CLAUDE.md 3.1/3.3. this.socket is definitely
      // non-null here (we just created it above); the `!` tells TypeScript
      // that, since it can't see across the event-listener closure boundary.
      this.socket!.send(token);
      this.connectionState.set('open');
    });

    this.socket.addEventListener('message', (event: MessageEvent<string>) => {
      const parsed = JSON.parse(event.data) as ServerChatEvent;
      this.eventsSubject.next(parsed);

      // Double tick (build-order step 9): the instant this client receives
      // a live-delivered message, it confirms that automatically — no user
      // action, no "mark as read" button. This is what makes double tick
      // mean "reached the device," the same as WhatsApp, rather than "the
      // user opened the chat" (that's a read receipt / blue tick, still out
      // of scope — CLAUDE.md section 5).
      if (parsed.type === 'incoming_message') {
        this.sendDeliveredAck(parsed);
      }
    });

    this.socket.addEventListener('close', (event: CloseEvent) => {
      this.connectionState.set('closed');
      if (event.code === 1008) {
        this.authFailed.set(true);
      }
      this.socket = null;
    });
  }

  /**
   * Sends a chat message and returns the client-generated messageId — the
   * correlation token ChatComponent uses to match a later TickAck back to
   * the right bubble in its own message list (see CLAUDE.md 3.1 for why
   * this id is client-generated rather than server-assigned).
   * crypto.randomUUID() is a standard browser API, no extra library needed.
   */
  sendMessage(recipientId: string, content: string): string {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      throw new Error('Cannot send a message: chat connection is not open');
    }

    const messageId = crypto.randomUUID();
    const request: ChatMessageRequest = { type: 'message', messageId, recipientId, content };
    this.socket.send(JSON.stringify(request));
    return messageId;
  }

  /**
   * senderId is just read back off the IncomingChatMessage we already
   * received — see chat.models.ts's DeliveredAck comment and
   * chat-service's DeliveredAck.java for why the client supplies it rather
   * than chat-service tracking a messageId -> senderId map of its own.
   * Not exposed as a public method: nothing outside this class should ever
   * need to send one directly, since it's entirely a reaction to a message
   * arriving, not a user-triggered action like sendMessage() is.
   */
  private sendDeliveredAck(message: IncomingChatMessage): void {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      // The socket closed in the gap between receiving the message and
      // acknowledging it — nothing useful to do; the sender simply won't
      // get a double tick for this message, same as any other
      // "recipient's connection dropped" case in this protocol.
      return;
    }

    const ack: DeliveredAck = { type: 'delivered_ack', messageId: message.messageId, senderId: message.senderId };
    this.socket.send(JSON.stringify(ack));
  }

  disconnect(): void {
    this.socket?.close();
    this.socket = null;
  }
}
