package vn.bando.spatial.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ReviewerGate {

    private final String name;
    private final String token;

    public ReviewerGate(
            @Value("${bando.reviewer.name:}") String name,
            @Value("${bando.reviewer.token:}") String token) {
        this.name = name == null ? "" : name.trim();
        this.token = token == null ? "" : token;
    }

    public String require(String provided) {
        if (name.isEmpty() || token.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa cấu hình người duyệt");
        }
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        byte[] actual = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần mã người duyệt");
        }
        return name;
    }
}
