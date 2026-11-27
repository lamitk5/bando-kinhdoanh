package vn.bando.spatial.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component
public class ReviewerGate {

    private final List<Account> accounts;

    public ReviewerGate(
            @Value("${bando.reviewer.name:}") String legacyName,
            @Value("${bando.reviewer.token:}") String legacyToken,
            @Value("${bando.reviewers:}") String roster) {
        var byName = new LinkedHashMap<String, Account>();
        var tokens = new LinkedHashMap<String, String>();
        add(byName, tokens, legacyName, legacyToken);
        if (roster != null && !roster.isBlank()) {
            for (String raw : roster.split(";")) {
                if (raw.isBlank()) {
                    continue;
                }
                int bar = raw.indexOf('|');
                if (bar <= 0 || bar >= raw.length() - 1) {
                    throw new IllegalStateException("Mỗi tài khoản người duyệt có dạng tên|mã, các tài khoản cách nhau bởi dấu chấm phẩy");
                }
                add(byName, tokens, raw.substring(0, bar), raw.substring(bar + 1).trim());
            }
        }
        this.accounts = List.copyOf(byName.values());
    }

    public String require(String provided) {
        if (accounts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa cấu hình người duyệt");
        }
        byte[] actual = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        String matched = null;
        for (Account account : accounts) {
            if (MessageDigest.isEqual(account.token.getBytes(StandardCharsets.UTF_8), actual)) {
                matched = account.name;
            }
        }
        if (matched == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần mã người duyệt");
        }
        return matched;
    }

    public ObjectNode directory(ObjectMapper mapper) {
        ObjectNode body = mapper.createObjectNode();
        body.put("account_count", accounts.size());
        var names = body.putArray("names");
        accounts.forEach(account -> names.add(account.name));
        body.put("note", accounts.isEmpty()
                ? "Chưa cấu hình tài khoản người duyệt. Mã không nằm trong kho mã."
                : "Mỗi tên là một tài khoản người duyệt, mỗi người một mã. Mã không được trả về. Chưa có đăng nhập bằng mật khẩu.");
        return body;
    }

    public List<String> names() {
        return accounts.stream().map(account -> account.name).toList();
    }

    private static void add(LinkedHashMap<String, Account> byName, LinkedHashMap<String, String> tokens, String name, String token) {
        String cleanName = name == null ? "" : name.trim();
        String cleanToken = token == null ? "" : token;
        if (cleanName.isEmpty() && cleanToken.isEmpty()) {
            return;
        }
        if (cleanName.isEmpty() || cleanToken.isEmpty() || cleanName.length() > 80 || cleanName.indexOf('|') >= 0) {
            throw new IllegalStateException("Tài khoản người duyệt cần tên từ 1 đến 80 ký tự và một mã riêng");
        }
        String nameKey = cleanName.toLowerCase(Locale.ROOT);
        Account existing = byName.get(nameKey);
        if (existing != null) {
            if (!existing.token.equals(cleanToken)) {
                throw new IllegalStateException("Một tên người duyệt không được có hai mã");
            }
            return;
        }
        String tokenOwner = tokens.putIfAbsent(cleanToken, cleanName);
        if (tokenOwner != null) {
            throw new IllegalStateException("Hai người duyệt không được dùng chung một mã");
        }
        byName.put(nameKey, new Account(cleanName, cleanToken));
    }

    private record Account(String name, String token) {
    }
}
