package com.chatapp.userservice.service;

import com.chatapp.userservice.dto.RegisterRequest;
import com.chatapp.userservice.dto.RegisterResponse;
import com.chatapp.userservice.dto.UserListResponse;
import com.chatapp.userservice.dto.UserSummary;
import com.chatapp.userservice.entity.User;
import com.chatapp.userservice.exception.DuplicateUsernameException;
import com.chatapp.userservice.repository.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The "service" layer: business logic lives here, not in the controller.
 *
 * Why not just put this in the controller? Two reasons that matter even at
 * this small scale:
 *   1. Separation of concerns — the controller's job is HTTP (parse the
 *      request, pick a status code, shape the response). Deciding "is this
 *      username taken", "hash before storing" is application logic that
 *      doesn't care whether it was triggered by HTTP, a CLI, or a test.
 *   2. Testability — this class can be unit-tested (or mocked, as
 *      UserControllerTest does) without spinning up a web server or a real
 *      database.
 *
 * The controller calls this class; this class calls the repository. Logic
 * never skips a layer (e.g. the controller never talks to UserRepository
 * directly) so there's exactly one place responsible for each concern.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // Constructor injection (rather than @Autowired fields): makes the
    // dependencies explicit and required, and is what lets UserControllerTest
    // (a @WebMvcTest, not a full application context) substitute a mock
    // UserService without needing a real one of these at all.
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registers a new user. Required-field validation (username/password/
     * firstName non-blank) has already happened by the time this runs — it's
     * enforced declaratively on RegisterRequest via @Valid at the controller
     * boundary, so this method only needs to handle the checks that require
     * looking at existing data: the duplicate-username check.
     */
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateUsernameException(request.getUsername());
        }

        // Hash here, immediately before constructing the entity — the plaintext
        // password from the request never gets stored anywhere or passed any
        // further than this line.
        String hashedPassword = passwordEncoder.encode(request.getPassword());

        User user = new User(
                request.getUsername(),
                hashedPassword,
                request.getFirstName(),
                request.getLastName()
        );

        User saved = userRepository.save(user);

        return new RegisterResponse(saved.getId(), saved.getUsername(), saved.getFirstName(), saved.getLastName());
    }

    /**
     * Lists every registered user (CLAUDE.md section 2, build-order step 4).
     *
     * Sort.by("id") isn't strictly required by the spec, but findAll() with
     * no explicit sort doesn't guarantee any particular row order — adding it
     * costs nothing and means the same request returns users in the same
     * order every time, rather than an order that happens to match today's
     * database internals but isn't actually promised anywhere.
     */
    public UserListResponse listUsers() {
        List<UserSummary> summaries = userRepository.findAll(Sort.by("id")).stream()
                .map(user -> new UserSummary(user.getId(), user.getUsername(), user.getFirstName(),
                        user.getLastName(), user.getUserType().name()))
                .toList();

        String message = summaries.isEmpty()
                ? "No registered users found."
                : summaries.size() + " user(s) found.";

        return new UserListResponse(summaries, message);
    }
}
