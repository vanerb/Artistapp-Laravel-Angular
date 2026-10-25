package com.artistapp.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Service
public class JwtService {
    private final byte[] secret;
    private final long expirationMinutes;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getUrlDecoder();

    public JwtService(@Value("${artistapp.jwt.secret}") String secret,
                      @Value("${artistapp.jwt.expiration-minutes:720}") long expirationMinutes) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) throw new IllegalArgumentException("JWT_SECRET must contain at least 32 characters");
        this.expirationMinutes = expirationMinutes;
    }

    public String createToken(long userId) {
        try {
            String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            long now = Instant.now().getEpochSecond();
            String payload = encoder.encodeToString(mapper.writeValueAsBytes(Map.of(
                "sub", Long.toString(userId), "iat", now, "exp", now + expirationMinutes * 60
            )));
            String unsigned = header + "." + payload;
            return unsigned + "." + encoder.encodeToString(sign(unsigned));
        } catch (Exception e) {
            throw new IllegalStateException("Could not create access token", e);
        }
    }

    public Long getUserId(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            String unsigned = parts[0] + "." + parts[1];
            if (!java.security.MessageDigest.isEqual(sign(unsigned), decoder.decode(parts[2]))) return null;
            Map<?, ?> claims = mapper.readValue(decoder.decode(parts[1]), Map.class);
            Object exp = claims.get("exp");
            Object sub = claims.get("sub");
            if (!(exp instanceof Number n) || n.longValue() <= Instant.now().getEpochSecond() || sub == null) return null;
            return Long.parseLong(sub.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
}