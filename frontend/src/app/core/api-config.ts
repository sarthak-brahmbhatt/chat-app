/**
 * Backend base URLs, re-exported from the active environment file rather
 * than hardcoded here.
 *
 * These were originally plain hardcoded constants, with a comment saying
 * Angular's environment/fileReplacements machinery was "for swapping config
 * between build TARGETS" and this app only had one target. That stopped
 * being true once the app was actually deployed (build-order step 12): a
 * local build must talk to localhost over http/ws, and a production build
 * must talk to api.sarthak-chat-app.beer over https/wss — genuinely two
 * targets, which is exactly what fileReplacements exists for. See
 * src/environments/environment.ts and environment.production.ts, and
 * angular.json's production configuration for the swap itself.
 *
 * Kept as a thin re-export instead of deleting the module and rewriting
 * every import: the consumers (AuthService, UserService, ChatService) don't
 * care where the value comes from, so there's no reason to churn them.
 */
import { environment } from '../../environments/environment';

export const USER_SERVICE_BASE_URL = environment.userServiceBaseUrl;
export const CHAT_SERVICE_WS_URL = environment.chatServiceWsUrl;
