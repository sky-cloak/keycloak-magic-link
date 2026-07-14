package io.skycloak.keycloak.magiclink;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.SingleUseObjectProvider;

class MagicLinkPendingStoreTest {
    @Test
    void registersOnlyNonNullIdsAndPreservesMetadata() {
        KeycloakSession session = mock(KeycloakSession.class);
        SingleUseObjectProvider store = mock(SingleUseObjectProvider.class);
        when(session.singleUseObjects()).thenReturn(store);

        MagicLinkPendingStore.register(session, "id", 60, null, "client");
        MagicLinkPendingStore.register(session, null, 60, "user", "client");

        verify(store).put(eq("skycloak-ml-pending:id"), eq(60L),
                eq(Map.of("uid", "", "cid", "client")));
        verify(store, never()).put(eq("skycloak-ml-pending:null"), anyLong(), any());
    }

    @Test
    void consumeAndRevokeReflectWhetherTheEntryExists() {
        KeycloakSession session = mock(KeycloakSession.class);
        SingleUseObjectProvider store = mock(SingleUseObjectProvider.class);
        when(session.singleUseObjects()).thenReturn(store);
        when(store.remove("skycloak-ml-pending:present")).thenReturn(Map.of());
        when(store.remove("skycloak-ml-pending:gone")).thenReturn(null);

        assertTrue(MagicLinkPendingStore.consume(session, "present"));
        assertFalse(MagicLinkPendingStore.consume(session, "gone"));
        assertFalse(MagicLinkPendingStore.consume(session, null));
        assertTrue(MagicLinkPendingStore.revoke(session, "present"));
    }
}
