package io.skycloak.keycloak.magiclink;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

class MagicLinkEmailTest {
    @Test
    void usesTheThemeTemplateWhenItIsAvailable() throws Exception {
        KeycloakSession session = mock(KeycloakSession.class);
        RealmModel realm = mock(RealmModel.class);
        UserModel user = mock(UserModel.class);
        EmailTemplateProvider template = mock(EmailTemplateProvider.class);
        when(session.getProvider(EmailTemplateProvider.class)).thenReturn(template);
        when(template.setRealm(realm)).thenReturn(template);
        when(template.setUser(user)).thenReturn(template);

        MagicLinkEmail.send(session, realm, user, "https://link", "App");

        verify(template).send(eq(MagicLinkEmail.SUBJECT_KEY), eq(MagicLinkEmail.TEMPLATE),
                eq(java.util.Map.of("link", "https://link", "clientName", "App")));
    }

    @Test
    void fallsBackToEscapedBuiltInEmailWhenThemeRenderingFails() throws Exception {
        KeycloakSession session = mock(KeycloakSession.class);
        RealmModel realm = mock(RealmModel.class);
        UserModel user = mock(UserModel.class);
        EmailTemplateProvider template = mock(EmailTemplateProvider.class);
        EmailSenderProvider sender = mock(EmailSenderProvider.class);
        when(session.getProvider(EmailTemplateProvider.class)).thenReturn(template);
        when(session.getProvider(EmailSenderProvider.class)).thenReturn(sender);
        when(template.setRealm(realm)).thenReturn(template);
        when(template.setUser(user)).thenReturn(template);
        doThrow(new EmailException("theme unavailable")).when(template).send(any(), any(), any());
        when(realm.getSmtpConfig()).thenReturn(java.util.Map.of());
        when(user.getEmail()).thenReturn("alice@example.test");

        MagicLinkEmail.send(session, realm, user, "https://x/?a=<b>", "A & B");

        verify(sender).send(eq(java.util.Map.of()), eq("alice@example.test"), eq("Your sign-in link"),
                org.mockito.ArgumentMatchers.contains("Sign in to A & B"),
                org.mockito.ArgumentMatchers.contains("A &amp; B"));
    }
}
