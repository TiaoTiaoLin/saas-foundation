package com.saas.foundation.integration.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.core.net.AbstractHttpResponse;
import com.resend.core.net.HttpMethod;
import com.resend.core.net.IHttpClient;

@ExtendWith(MockitoExtension.class)
class ResendEmailProviderClientTest {

    private static final EmailMessage MESSAGE = new EmailMessage(
            "admin@example.com",
            "Welcome",
            "Your tenant is ready.");

    private final ObjectMapper json = new ObjectMapper();

    @Mock
    private IHttpClient<String> http;

    @Test
    void sendsPlainTextAndReturnsTheResendMessageId() throws Exception {
        when(http.perform(eq("/emails"), eq("re_test"), eq(HttpMethod.POST), anyString(), any()))
                .thenReturn(new AbstractHttpResponse<>(200, "{\"id\":\"msg_123\"}", true));
        ResendEmailProviderClient client = client();

        EmailSendResult result = client.send(MESSAGE);

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("msg_123");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(http).perform(eq("/emails"), eq("re_test"), eq(HttpMethod.POST), payload.capture(), any());
        JsonNode body = json.readTree(payload.getValue());
        assertThat(body.path("from").asText()).isEqualTo("Acme <hello@example.com>");
        JsonNode to = body.path("to");
        assertThat(to.isArray()).isTrue();
        assertThat(to.size()).isEqualTo(1);
        assertThat(to.get(0).asText()).isEqualTo("admin@example.com");
        assertThat(body.path("subject").asText()).isEqualTo("Welcome");
        assertThat(body.path("text").asText()).isEqualTo("Your tenant is ready.");
        assertThat(body.has("html")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503})
    void throwsOnceWhenResendReturnsARetryableStatus(int status) {
        when(http.perform(eq("/emails"), eq("re_test"), eq(HttpMethod.POST), anyString(), any()))
                .thenReturn(new AbstractHttpResponse<>(status, "{\"message\":\"unavailable\"}", false));

        assertThatThrownBy(() -> client().send(MESSAGE))
                .isInstanceOf(EmailProviderException.class)
                .hasMessage("Resend email send failed")
                .cause()
                .isInstanceOfSatisfying(ResendException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(status));
        verify(http, times(1)).perform(eq("/emails"), eq("re_test"), eq(HttpMethod.POST), anyString(), any());
    }

    @Test
    void doesNotThrowWhenResendRejectsTheMessage() {
        when(http.perform(eq("/emails"), eq("re_test"), eq(HttpMethod.POST), anyString(), any()))
                .thenReturn(new AbstractHttpResponse<>(422, "{\"message\":\"Invalid `from` field\"}", false));

        EmailSendResult result = client().send(MESSAGE);

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).isEqualTo("rejected");
        verify(http, times(1)).perform(eq("/emails"), eq("re_test"), eq(HttpMethod.POST), anyString(), any());
    }

    @Test
    void requiresAnApiKeyAndFromAddress() {
        assertThatThrownBy(() -> new ResendEmailProviderClient(new EmailProperties("  ", "Acme <hello@example.com>")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Set RESEND_API_KEY");
        assertThatThrownBy(() -> new ResendEmailProviderClient(new EmailProperties("re_test", " ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Set RESEND_FROM");
    }

    private ResendEmailProviderClient client() {
        Resend resend = Resend.builder().apiKey("re_test").httpClient(http).build();
        return new ResendEmailProviderClient(resend, "Acme <hello@example.com>");
    }
}
