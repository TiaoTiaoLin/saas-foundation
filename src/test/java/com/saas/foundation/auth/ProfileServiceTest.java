package com.saas.foundation.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;

class ProfileServiceTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private ProfileService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new ProfileService(users);
        user = new UserAccount("ada@acme.test", "hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
    }

    @Test
    void updateStoresTheTrimmedName() {
        ProfileResponse response = service.update(user.getId(), "  Ada Lovelace  ");

        assertThat(response.name()).isEqualTo("Ada Lovelace");
        assertThat(response.email()).isEqualTo("ada@acme.test");
        assertThat(user.getName()).isEqualTo("Ada Lovelace");
        assertThat(service.get(user.getId()).name()).isEqualTo("Ada Lovelace");
    }

    @Test
    void blankNameIsRejected() {
        user.setName("Ada");

        assertThatThrownBy(() -> service.update(user.getId(), "   "))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getReason()).isEqualTo(UserName.REQUIRED);
                });
        assertThat(user.getName()).isEqualTo("Ada");
    }

    @Test
    void disabledUserCannotReadOrChangeTheName() {
        user.setEnabled(false);

        assertThatThrownBy(() -> service.get(user.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThatThrownBy(() -> service.update(user.getId(), "Ada"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(user.getName()).isEmpty();
    }
}
