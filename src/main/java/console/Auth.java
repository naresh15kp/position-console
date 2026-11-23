package console;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Component
class Auth {
    record User(String username, String role) {}
    private record Account(User user, String password) {}
    private final Map<String, Account> accounts = new HashMap<>();
    private final byte[] key;
    private static final int TTL = 28800;
    private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODE = Base64.getUrlDecoder();

    Auth(ObjectMapper json, @Value("${USERS_FILE}") String users, @Value("${TOKEN_SECRET}") String secret)
            throws Exception {
        key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) throw new IllegalArgumentException("TOKEN_SECRET must be at least 32 bytes");
        JsonNode input = json.readTree(Path.of(users).toFile());
        if (!input.isArray()) throw new IllegalArgumentException("Expected an array of accounts");
        for (JsonNode line : input) {
            var user = new User(line.path("username").asText(), line.path("role").asText());
            String password = line.path("password").asText();
            if (user.username().isBlank() || password.isEmpty()
                    || !Set.of("INGEST", "OPERATOR", "SUPERVISOR").contains(user.role())
                    || accounts.putIfAbsent(user.username(), new Account(user, password)) != null) {
                throw new IllegalArgumentException("Invalid account configuration");
            }
        }
        if (accounts.isEmpty()) throw new IllegalArgumentException("No accounts configured");
    }

    Map<String, Object> login(JsonNode body) {
        Account account = accounts.get(body.path("username").asText());
        if (account == null || !MessageDigest.isEqual(bytes(account.password()), bytes(body.path("password").asText()))) {
            throw new Problem(401, "UNAUTHENTICATED");
        }
        String data = ENCODE.encodeToString(bytes(account.user().username())) + "." + (Instant.now().getEpochSecond() + TTL);
        return Map.of("accessToken", data + "." + ENCODE.encodeToString(sign(data)),
                "tokenType", "Bearer", "expiresInSec", TTL, "user", account.user());
    }

    User require(String header, String... roles) {
        User user = authenticate(header);
        if (roles.length > 0 && !Arrays.asList(roles).contains(user.role())) throw new Problem(403, "FORBIDDEN");
        return user;
    }

    private User authenticate(String header) {
        if (header != null && header.startsWith("Bearer ")) {
            try {
                String[] token = header.substring(7).split("\\.", -1);
                if (token.length == 3 && MessageDigest.isEqual(sign(token[0] + "." + token[1]), DECODE.decode(token[2]))
                        && Long.parseLong(token[1]) > Instant.now().getEpochSecond()) {
                    Account account = accounts.get(new String(DECODE.decode(token[0]), StandardCharsets.UTF_8));
                    if (account != null) return account.user();
                }
            } catch (IllegalArgumentException ignored) { }
        }
        throw new Problem(401, "UNAUTHENTICATED");
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(bytes(value));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}