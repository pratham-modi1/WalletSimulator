package com.aureus.aureus.security;

import com.aureus.aureus.entity.User;
import com.aureus.aureus.entity.UserStatus;
import com.aureus.aureus.service.JwtService;
import com.aureus.aureus.repository.UserRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthFilter(
            JwtService jwtService,
            UserRepository userRepository,
            AuthenticationEntryPoint authenticationEntryPoint) {

        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    // Authentication endpoints are public.
    // JWT authentication is unnecessary for registration and login.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {

        String path = request.getServletPath();

        return path.equals("/api/auth")
                || path.startsWith("/api/auth/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        // STEP 1: Read Authorization header.
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        // No Bearer token: continue.
        // SecurityConfig will decide whether authentication is required.
        if (header == null ||
                !header.regionMatches(true, 0, "Bearer ", 0, 7)) {

            filterChain.doFilter(request, response);
            return;
        }

        // STEP 2: Extract the JWT.
        String token = header.substring(7).trim();

        if (token.isEmpty()) {
            rejectRequest(request, response, null);
            return;
        }

        try {

            // STEP 3: Verify JWT signature and expiry.
            Claims claims = jwtService.extractClaims(token);

            // STEP 4: Extract userId from subject.
            Integer userId = Integer.valueOf(claims.getSubject());

            // STEP 5: Extract role claim.
            String tokenRole = claims.get("role", String.class);

            // STEP 6: Load actual user from database.
            User user = userRepository.findById(userId)
                    .orElseThrow(() ->
                            new BadCredentialsException("Invalid token")
                    );

            // STEP 7: Only ACTIVE users are allowed.
            if (user.getUserStatus() != UserStatus.ACTIVE) {
                throw new BadCredentialsException("User is inactive");
            }

            // Ensure the token's role is consistent with the current DB role.
            if (user.getRole() == null ||
                    !user.getRole().name().equals(tokenRole)) {

                throw new BadCredentialsException("Invalid token role");
            }

            // STEP 8: Construct our custom principal.
            AuthenticatedUser principal = new AuthenticatedUser(
                    user.getUserId(),
                    user.getEmail(),
                    user.getRole()
            );

            // STEP 9: Create authority from current database role.
            List<SimpleGrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority(
                            "ROLE_" + user.getRole().name()
                    )
            );

            // STEP 10: Create Authentication object.
            Authentication authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            authorities
                    );

            // Attach request details.
            ((UsernamePasswordAuthenticationToken) authentication)
                    .setDetails(
                            new WebAuthenticationDetailsSource()
                                    .buildDetails(request)
                    );

            // STEP 11: Store authentication in SecurityContext.
            SecurityContext context =
                    SecurityContextHolder.createEmptyContext();

            context.setAuthentication(authentication);

            SecurityContextHolder.setContext(context);

        } catch (JwtException |
                 IllegalArgumentException |
                 AuthenticationException exception) {

            rejectRequest(request, response, exception);
            return;
        }

        // STEP 12: Continue the remaining filter chain.
        filterChain.doFilter(request, response);
    }


    private void rejectRequest(
            HttpServletRequest request,
            HttpServletResponse response,
            Exception exception)
            throws IOException, ServletException {

        SecurityContextHolder.clearContext();

        authenticationEntryPoint.commence(
                request,
                response,
                new BadCredentialsException(
                        "Invalid or expired bearer token",
                        exception
                )
        );
    }
}