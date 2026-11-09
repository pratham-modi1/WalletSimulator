package com.aureus.aureus.service;

import com.aureus.aureus.entity.Role;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;

import java.util.Date;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final JwtParser jwtParser;
     private final long expirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms:3600000}") long expirationMs) {

        if (expirationMs <= 0) {
            throw new IllegalArgumentException(
                    "JWT expiration must be positive"
            );
        }

        // Decode the Base64 secret into its original bytes.
        byte[] keyBytes = Decoders.BASE64.decode(secret);

        // Create a secure HMAC key.
        // JJWT rejects keys weaker than 256 bits.
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);

        this.expirationMs = expirationMs;

        // Build a parser that verifies tokens using our signing key.
        this.jwtParser = Jwts.parser()
                .verifyWith(signingKey)
                .build();
    }

    // Generate JWT
    public String generateToken(Integer userId, Role role) {

        Date now = new Date();

        Date expiry = new Date(
                now.getTime() + expirationMs
        );

        return Jwts.builder()

                // Subject = user ID
                .subject(String.valueOf(userId))

                // Custom claim = user role
                .claim("role", role.name())

                // Token creation time
                .issuedAt(now)

                // Token expiry time
                .expiration(expiry)

                // Sign using HS256
                .signWith(signingKey, Jwts.SIG.HS256)

                // Generate compact JWT string
                .compact();
    }

    // Extract claims after verifying the signature and expiry.
    public Claims extractClaims(String token) {

        return jwtParser
                .parseSignedClaims(token)
                .getPayload();
    }

    // Return token lifetime in seconds.
    public long getExpirationSeconds() {

        return expirationMs / 1000;
    }
}