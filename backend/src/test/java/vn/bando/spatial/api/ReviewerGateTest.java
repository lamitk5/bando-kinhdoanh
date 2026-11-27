package vn.bando.spatial.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

class ReviewerGateTest {

    @Test
    void eachPersonHasASeparateToken() {
        ReviewerGate gate = new ReviewerGate("ban-kiem-tra", "kiem-tra-noi-bo", "nguoi-duyet-khac|ma-rieng");
        assertEquals(java.util.List.of("ban-kiem-tra", "nguoi-duyet-khac"), gate.names());
        assertEquals("ban-kiem-tra", gate.require("kiem-tra-noi-bo"));
        assertEquals("nguoi-duyet-khac", gate.require("ma-rieng"));
        ResponseStatusException missing = assertThrows(ResponseStatusException.class, () -> gate.require(null));
        assertEquals(401, missing.getStatusCode().value());
        var directory = gate.directory(new ObjectMapper());
        assertEquals(2, directory.path("account_count").asInt());
        assertTrue(directory.path("note").asText().contains("một mã"));
        assertTrue(!directory.toString().contains("ma-rieng"));
        assertTrue(!directory.toString().contains("kiem-tra-noi-bo"));
    }

    @Test
    void aSharedTokenIsRejected() {
        assertThrows(IllegalStateException.class, () -> new ReviewerGate("Một", "chung", "Hai|chung"));
        assertThrows(IllegalStateException.class, () -> new ReviewerGate("Một", "ma-mot", "Một|ma-khac"));
        ReviewerGate none = new ReviewerGate("", "", "");
        ResponseStatusException unavailable = assertThrows(ResponseStatusException.class, () -> none.require("bat-ky"));
        assertEquals(503, unavailable.getStatusCode().value());
    }
}
