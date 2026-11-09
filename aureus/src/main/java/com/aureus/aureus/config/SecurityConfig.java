package com.aureus.aureus.config;

import com.aureus.aureus.repository.UserRepository;
import com.aureus.aureus.security.JwtAuthFilter;
import com.aureus.aureus.security.JsonAuthenticationEntryPoint;
import com.aureus.aureus.service.JwtService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    // Existing BCrypt bean: keep this.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            UserRepository userRepository,
            JsonAuthenticationEntryPoint authenticationEntryPoint)
            throws Exception {

        // Create JWT filter with its required dependencies.
        JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(
                jwtService,
                userRepository,
                authenticationEntryPoint
        );

        http

            // 1. Disable CSRF for stateless Bearer-token API.
            .csrf(csrf -> csrf.disable())

            // 2. Disable browser-based authentication mechanisms.
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())

            // 3. No HTTP sessions.
            .sessionManagement(session ->
                    session.sessionCreationPolicy(
                            SessionCreationPolicy.STATELESS
                    )
            )

            // 4. Custom JSON 401 handler.
            .exceptionHandling(exception ->
                    exception.authenticationEntryPoint(
                            authenticationEntryPoint
                    )
            )

            // 5. Endpoint authorization rules.
            .authorizeHttpRequests(auth -> auth

                    // Registration and login are public.
                    .requestMatchers("/api/auth/**").permitAll()

                    // Everything else requires authentication.
                    .anyRequest().authenticated()
            )

            // 6. Insert JWT filter into the security chain.
            .addFilterBefore(
                    jwtAuthFilter,
                    UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }
}