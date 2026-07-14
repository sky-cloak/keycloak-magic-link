package io.skycloak.keycloak.magiclink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;

class MagicLinkResourceHelperTest {
    @Test
    void returnsHealthAndFormatsSmallHelpersSafely() throws Exception {
        MagicLinkResource resource = new MagicLinkResource(mock(KeycloakSession.class));
        assertEquals(200, resource.health().getStatus());
        assertEquals("alice@example.test", callStatic("normalize", " Alice@Example.Test "));
        assertEquals("", callStatic("normalize", new Object[] {null}));
        assertEquals(true, callStatic("blank", " "));
        assertEquals(false, callStatic("blank", "x"));
        assertEquals("the application", callStatic("clientName", new Object[] {null}));
        assertEquals("{\"error\":\"bad\",\"error_description\":\"&lt;x&gt;\"}",
                callStatic("err", "bad", "<x>"));
    }

    @Test
    void resolvesUsersByPriorityAndHandlesMissingRealm() throws Exception {
        KeycloakSession session = mock(KeycloakSession.class);
        KeycloakContext context = mock(KeycloakContext.class);
        RealmModel realm = mock(RealmModel.class);
        UserProvider users = mock(UserProvider.class);
        UserModel user = mock(UserModel.class);
        when(session.users()).thenReturn(users);
        when(session.getContext()).thenReturn(context);
        when(users.getUserById(realm, "id")).thenReturn(user);
        when(users.getUserByUsername(realm, "alice")).thenReturn(user);
        MagicLinkResource resource = new MagicLinkResource(session);

        MagicLinkResource.IssueRequest byId = new MagicLinkResource.IssueRequest(); byId.userId = "id";
        MagicLinkResource.IssueRequest byName = new MagicLinkResource.IssueRequest(); byName.username = "alice";
        MagicLinkResource.IssueRequest empty = new MagicLinkResource.IssueRequest();
        assertEquals(user, call(resource, "resolveUser", realm, byId));
        assertEquals(user, call(resource, "resolveUser", realm, byName));
        assertNull(call(resource, "resolveUser", realm, empty));
        assertThrows(jakarta.ws.rs.NotFoundException.class, () -> call(resource, "realm"));
    }

    @Test
    void namesClientsAndRejectsBlankTokens() throws Exception {
        ClientModel client = mock(ClientModel.class);
        when(client.getName()).thenReturn(" ");
        when(client.getClientId()).thenReturn("client");
        assertEquals("client", callStatic("clientName", client));
        MagicLinkResource resource = new MagicLinkResource(mock(KeycloakSession.class));
        assertNull(call(resource, "decode", " "));
    }

    private static Object callStatic(String name, Object... args) throws Exception {
        return call(null, name, args);
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        Method method = java.util.Arrays.stream(MagicLinkResource.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name) && m.getParameterCount() == args.length).findFirst().orElseThrow();
        method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
}
