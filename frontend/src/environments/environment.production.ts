/**
 * PRODUCTION environment — swapped in for environment.ts at build time by
 * angular.json's `fileReplacements` under the production configuration.
 * Resolves the TODO left in cloudformation/frontend-stack.yaml and
 * .github/workflows/deploy-frontend.yml, which both flagged that the
 * production build had no real backend URL yet.
 *
 * Both URLs point at api.sarthak-chat-app.beer — the custom subdomain
 * dedicated to the ALB/backend (CLAUDE.md 3.8) — NOT at the ALB's own
 * *.elb.amazonaws.com name. That matters for two independent reasons:
 *
 *   1. TLS. The ALB's HTTPS listener presents the ACM certificate issued
 *      for api.sarthak-chat-app.beer. Requesting the raw ALB DNS name over
 *      HTTPS therefore fails certificate validation in a browser (the name
 *      doesn't match the cert), even though the ALB answers fine.
 *   2. Mixed content. CloudFront serves this app over HTTPS and browsers
 *      block HTTPS pages from calling http:// endpoints — so plain HTTP to
 *      the ALB isn't an option either. Both halves of this are exactly the
 *      reasoning CLAUDE.md 3.8 gives for why the custom domain is
 *      "required, not cosmetic."
 *
 * wss:// (not ws://) for the same mixed-content reason: a browser refuses
 * an insecure WebSocket opened from a secure page. The ALB terminates TLS
 * and forwards to chat-service over plain HTTP internally, so chat-service
 * itself still speaks ws — only the browser-facing hop is wss.
 *
 * The /ws/chat path is what the ALB's listener rule routes to the
 * chat-service target group (everything else falls through to
 * user-service) — see chat-app-stack.yaml's ChatServiceListenerRule.
 */
export const environment = {
  production: true,
  userServiceBaseUrl: 'https://api.sarthak-chat-app.beer',
  chatServiceWsUrl: 'wss://api.sarthak-chat-app.beer/ws/chat',
};
