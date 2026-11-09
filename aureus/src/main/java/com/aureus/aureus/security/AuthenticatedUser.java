package com.aureus.aureus.security;

import com.aureus.aureus.entity.Role;

public record AuthenticatedUser(
        Integer userId,
        String email,
        Role role
) {
}