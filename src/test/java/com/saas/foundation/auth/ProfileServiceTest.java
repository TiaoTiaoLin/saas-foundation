package com.saas.foundation.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.storage.ImageFiles;
import com.saas.foundation.storage.MemoryObjectStorage;

class ProfileServiceTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final MemoryObjectStorage images = new MemoryObjectStorage();
    private ProfileService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new ProfileService(users, images);
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

    @Test
    void saveAvatarStoresAJpegAndReplacesThePreviousObject() throws Exception {
        ProfileResponse first = service.saveAvatar(user.getId(), png());
        String firstKey = user.getAvatarKey();

        ProfileResponse second = service.saveAvatar(user.getId(), png());

        assertThat(second.avatarUrl()).startsWith("https://images.test/avatars/" + user.getId() + "/");
        assertThat(user.getAvatarKey()).isNotEqualTo(firstKey);
        assertThat(images.contains(firstKey)).isFalse();
        assertThat(images.contains(user.getAvatarKey())).isTrue();
        assertThat(first.email()).isEqualTo("ada@acme.test");
    }

    @Test
    void clearAvatarRemovesTheObject() throws Exception {
        service.saveAvatar(user.getId(), png());
        String key = user.getAvatarKey();

        ProfileResponse cleared = service.clearAvatar(user.getId());

        assertThat(cleared.avatarUrl()).isNull();
        assertThat(user.getAvatarKey()).isNull();
        assertThat(images.contains(key)).isFalse();
    }

    @Test
    void saveAvatarRejectsAnSvg() {
        assertThatThrownBy(() -> service.saveAvatar(user.getId(), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes()))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(ImageFiles.UNSUPPORTED));
        assertThat(user.getAvatarKey()).isNull();
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x336699);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
