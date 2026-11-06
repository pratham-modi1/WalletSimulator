package com.aureus.aureus.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "User")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 5)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_status", nullable = false, length = 7)
    private UserStatus userStatus;

    // Required by JPA
    protected User() {
    }

    public User(String email, String passwordHash,
                Role role, UserStatus userStatus) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.userStatus = userStatus;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public UserStatus getUserStatus() {
        return userStatus;
    }
}