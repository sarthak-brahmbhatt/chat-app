package com.chatapp.userservice.service;

import com.chatapp.userservice.dto.RegisterRequest;
import com.chatapp.userservice.dto.RegisterResponse;
import com.chatapp.userservice.dto.UserListResponse;
import com.chatapp.userservice.entity.User;
import com.chatapp.userservice.exception.DuplicateUsernameException;
import com.chatapp.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A plain Mockito unit test for UserService — no Spring context involved at
 * all (contrast with UserControllerTest's @WebMvcTest, which boots the web
 * layer). This runs faster and, unlike the controller test, actually
 * exercises UserService's own logic: the duplicate-username check, the
 * password-hashing step, and the order the two happen in.
 *
 * UserRepository is a plain @Mock — its behavior (existsByUsername, save) is
 * stubbed per test, no real database involved.
 *
 * PasswordEncoder is deliberately a @Spy wrapping a real BCryptPasswordEncoder,
 * not a plain @Mock: a mock would let us assert "was encode() called with
 * this input" but would return whatever fake value we told it to, which
 * wouldn't prove real hashing happened. A spy runs the real implementation
 * (so we can assert the saved password is an actual bcrypt hash of the input)
 * while still recording invocations (so we can assert encode() was, or
 * wasn't, called at all — needed for the "hashing didn't happen" test below).
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @InjectMocks
    private UserService userService;

    private RegisterRequest newRequest() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("alice");
        request.setPassword("plaintext-password");
        request.setFirstName("Alice");
        request.setLastName("Smith");
        return request;
    }

    @Test
    void register_withNewUsername_savesUserWithBcryptHashedPassword() {
        RegisterRequest request = newRequest();
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        // In real JPA usage, save() is what assigns the generated id; here we
        // just hand back whatever User was passed in, since that's all
        // UserService needs to build the RegisterResponse from.
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RegisterResponse response = userService.register(request);

        ArgumentCaptor<User> savedUserCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUserCaptor.capture());
        User savedUser = savedUserCaptor.getValue();

        // Not the plaintext password...
        assertThat(savedUser.getPassword()).isNotEqualTo("plaintext-password");
        // ...but a genuine bcrypt hash of it. Bcrypt hashes always start with a
        // version prefix like "$2a$"; checking that plus a successful matches()
        // call together confirm this is a real bcrypt hash of the right input,
        // not just some other opaque string.
        assertThat(savedUser.getPassword()).startsWith("$2a$");
        assertThat(passwordEncoder.matches("plaintext-password", savedUser.getPassword())).isTrue();

        assertThat(response.getUsername()).isEqualTo("alice");
    }

    @Test
    void register_withExistingUsername_throwsAndNeverCallsSave() {
        RegisterRequest request = newRequest();
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThrows(DuplicateUsernameException.class, () -> userService.register(request));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void register_withExistingUsername_neverHashesPassword() {
        // Confirms the duplicate check runs BEFORE hashing. If the order were
        // reversed, every rejected registration would still pay for bcrypt's
        // deliberately-slow hashing for no reason — encode() should never even
        // be invoked on this path.
        RegisterRequest request = newRequest();
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThrows(DuplicateUsernameException.class, () -> userService.register(request));

        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void listUsers_withRegisteredUsers_mapsToSummariesWithoutPasswordAndBuildsCountMessage() {
        User alice = new User("alice", "hashed-alice-password", "Alice", "Smith");
        User bob = new User("bob", "hashed-bob-password", "Bob", "Jones");
        when(userRepository.findAll(Sort.by("id"))).thenReturn(List.of(alice, bob));

        UserListResponse response = userService.listUsers();

        assertThat(response.getUsers()).hasSize(2);
        assertThat(response.getUsers().get(0).getUsername()).isEqualTo("alice");
        assertThat(response.getUsers().get(0).getFirstName()).isEqualTo("Alice");
        assertThat(response.getUsers().get(1).getUsername()).isEqualTo("bob");
        assertThat(response.getMessage()).isEqualTo("2 user(s) found.");
    }

    @Test
    void listUsers_withNoRegisteredUsers_returnsEmptyListWithDescriptiveMessage() {
        when(userRepository.findAll(Sort.by("id"))).thenReturn(List.of());

        UserListResponse response = userService.listUsers();

        assertThat(response.getUsers()).isEmpty();
        assertThat(response.getMessage()).isEqualTo("No registered users found.");
    }
}
