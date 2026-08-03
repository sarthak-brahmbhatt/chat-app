package com.chatapp.userservice.repository;

import com.chatapp.userservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * The "repository" layer: the only place in the codebase that talks to the
 * User DB directly. Extending JpaRepository gives us save(), findById(), etc.
 * for free — Spring Data JPA generates the implementation at startup, so this
 * interface never needs a class body for those.
 *
 * existsByUsername and findByUsername are both "derived queries": Spring Data
 * JPA parses each method name itself and generates the matching SQL — no
 * @Query/SQL string needed here. They serve different callers: register
 * (UserService) only needs a yes/no ("existsByUsername") before deciding
 * whether to reject a duplicate; login (AuthService) needs the actual row
 * ("findByUsername") because it has to read the stored password hash to
 * verify it.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByUsername(String username);

    Optional<User> findByUsername(String username);
}
