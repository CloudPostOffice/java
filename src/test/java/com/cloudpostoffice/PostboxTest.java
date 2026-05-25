package com.cloudpostoffice;

import com.cloudpostoffice.exceptions.AuthenticationException;
import com.cloudpostoffice.exceptions.CloudPostOfficeException;
import com.cloudpostoffice.exceptions.ConnectionTimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostboxTest {

    // ── CloudPostOffice.configure() ───────────────────────────────────────────

    @Test
    void configure_acceptsHttpsUrl() {
        assertDoesNotThrow(() ->
                CloudPostOffice.configure(new CloudPostOffice.Config("https://cloudpostoffice.com")));
    }

    @Test
    void configure_acceptsLocalhostHttp() {
        assertDoesNotThrow(() ->
                CloudPostOffice.configure(new CloudPostOffice.Config("http://localhost:3000")));
    }

    @Test
    void configure_accepts127Http() {
        assertDoesNotThrow(() ->
                CloudPostOffice.configure(new CloudPostOffice.Config("http://127.0.0.1:8080")));
    }

    @Test
    void configure_rejectsPlainHttp() {
        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                CloudPostOffice.configure(new CloudPostOffice.Config("http://cloudpostoffice.com")));
        assertTrue(ex.getMessage().contains("https://"));
    }

    @Test
    void configure_nullOrEmptyIsNoop() {
        assertDoesNotThrow(() -> CloudPostOffice.configure(null));
        assertDoesNotThrow(() -> CloudPostOffice.configure(new CloudPostOffice.Config("")));
        assertDoesNotThrow(() -> CloudPostOffice.configure(new CloudPostOffice.Config(null)));
    }

    // ── CloudPostOffice.newPostbox() ──────────────────────────────────────────

    @Test
    void newPostbox_rejectsEmptyId() {
        assertThrows(IllegalArgumentException.class, () ->
                CloudPostOffice.newPostbox("", "secret"));
    }

    @Test
    void newPostbox_rejectsEmptySecret() {
        assertThrows(IllegalArgumentException.class, () ->
                CloudPostOffice.newPostbox("my-postbox", ""));
    }

    @Test
    void newPostbox_rejectsNullId() {
        assertThrows(IllegalArgumentException.class, () ->
                CloudPostOffice.newPostbox(null, "secret"));
    }

    // ── Topic name validation ─────────────────────────────────────────────────

    @Test
    void publish_rejectsSlashInTopicName() {
        Postbox p = CloudPostOffice.newPostbox("proj-test--p1", "secret");
        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                p.publish("bad/topic", "msg"));
        assertTrue(ex.getMessage().contains("/"));
    }

    @Test
    void publish_rejectsPlusInTopicName() {
        Postbox p = CloudPostOffice.newPostbox("proj-test--p2", "secret");
        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                p.publish("bad+topic", "msg"));
        assertTrue(ex.getMessage().contains("+"));
    }

    @Test
    void publish_rejectsHashInTopicName() {
        Postbox p = CloudPostOffice.newPostbox("proj-test--p3", "secret");
        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                p.publish("bad#topic", "msg"));
        assertTrue(ex.getMessage().contains("#"));
    }

    @Test
    void publish_rejectsDoubleHyphenInTopicName() {
        Postbox p = CloudPostOffice.newPostbox("proj-test--p4", "secret");
        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                p.publish("bad--topic", "msg"));
        assertTrue(ex.getMessage().contains("--"));
    }

    @Test
    void publish_rejectsEmptyTopicName() {
        Postbox p = CloudPostOffice.newPostbox("proj-test--p5", "secret");
        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                p.publish("", "msg"));
        assertTrue(ex.getMessage().toLowerCase().contains("empty") ||
                   ex.getMessage().toLowerCase().contains("non-empty"));
    }

    // ── Exception hierarchy ───────────────────────────────────────────────────

    @Test
    void authenticationException_isSubtypeOfCloudPostOfficeException() {
        AuthenticationException ex = new AuthenticationException("bad creds", 401);
        assertInstanceOf(CloudPostOfficeException.class, ex);
        assertEquals(401, ex.getStatus());
        assertTrue(ex.getMessage().contains("bad creds"));
    }

    @Test
    void connectionTimeoutException_isSubtypeOfCloudPostOfficeException() {
        ConnectionTimeoutException ex = new ConnectionTimeoutException("my-postbox");
        assertInstanceOf(CloudPostOfficeException.class, ex);
        assertTrue(ex.getMessage().contains("my-postbox"));
    }

    // ── send() argument validation ────────────────────────────────────────────

    @Test
    void send_rejectsEmptyTo() {
        Postbox p = CloudPostOffice.newPostbox("proj-test--p6", "secret");
        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                p.send("", "hello"));
        assertTrue(ex.getMessage().toLowerCase().contains("empty") ||
                   ex.getMessage().toLowerCase().contains("non-empty"));
    }

    @Test
    void send_rejectsNullTo() {
        Postbox p = CloudPostOffice.newPostbox("proj-test--p7", "secret");
        assertThrows(IllegalArgumentException.class, () -> p.send(null, "hello"));
    }
}
