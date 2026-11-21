package com.edura.schoolos.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtTokenService {
    private final SecretKey key;
    private final long expirationMinutes;

    public JwtTokenService(@Value("${app.jwt-secret}") String secret, @Value("${app.jwt-expiration-minutes}") long expirationMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    public String issue(String subject, UUID tenantId, String role) {
        Instant now = Instant.now();
        return Jwts.builder().subject(subject).claim("tenant_id", tenantId.toString()).claim("role", role)
            .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(expirationMinutes * 60)))
            .signWith(key, Jwts.SIG.HS256).compact();
    }
}
