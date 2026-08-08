import { Component } from '@angular/core';

/**
 * Rendered in ChatShellComponent's right panel when no conversation is
 * selected yet (the bare `/chat` route, before a `:userId` child activates).
 * Nothing to reuse here — this state didn't exist before the split view.
 */
@Component({
  selector: 'app-chat-placeholder',
  template: `<div class="chat-placeholder">Select a conversation to start chatting.</div>`,
})
export class ChatPlaceholderComponent {}
