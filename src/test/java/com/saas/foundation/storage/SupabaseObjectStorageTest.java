package com.saas.foundation.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SupabaseObjectStorageTest {

    @Test
    void aRelativeSignedPathBecomesAnAbsoluteUrl() {
        String url = SupabaseObjectStorage.absolute(
                "https://project.supabase.co/",
                "/object/sign/images/avatars/a.jpg?token=abc");

        assertThat(url).isEqualTo("https://project.supabase.co/storage/v1/object/sign/images/avatars/a.jpg?token=abc");
    }

    @Test
    void anAbsoluteSignedUrlIsLeftAlone() {
        String signed = "https://project.supabase.co/storage/v1/object/sign/images/a.jpg?token=abc";

        assertThat(SupabaseObjectStorage.absolute("https://project.supabase.co", signed)).isEqualTo(signed);
    }
}
