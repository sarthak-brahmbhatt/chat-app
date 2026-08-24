package com.chatapp.chatservice.entity;

/**
 * chat-service's copy of user-service's {@code UserType} enum (CLAUDE.md 3.9).
 *
 * <p>A deliberate small duplication, for the same reason JwtValidator duplicates
 * part of user-service's JwtService rather than sharing a module: these two
 * services have no shared build artifact, and introducing one for a two-constant
 * enum would be a large structural change to save eight lines. The constants must
 * stay in sync by hand; the STRING persistence on both sides means a drift shows
 * up as a Hibernate enum-parse failure on read, which is loud, rather than as a
 * silently wrong routing decision.
 */
public enum UserType {
    USER,
    BOT,

    /** The Version 2 tool-calling bot. A second bot user, alongside BOT, not replacing it. */
    BOT_TOOL
}
