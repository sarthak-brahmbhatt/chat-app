package com.chatapp.chatservice.repository;

import com.chatapp.chatservice.entity.AppUser;
import com.chatapp.chatservice.entity.UserType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Lookups against the {@code users} table (CLAUDE.md 3.9).
 *
 * <p>Read-only by construction: this interface deliberately exposes only finders.
 * JpaRepository does inherit save()/delete(), which is the unavoidable cost of
 * extending it — the boundary is that nothing in chat-service calls them, and
 * this comment is the reason a reviewer should push back if something starts to.
 * See {@link AppUser} for why user-service stays the sole writer.
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    List<AppUser> findByUserType(UserType userType);
}
