
package com.aureus.aureus.repository;

import com.aureus.aureus.entity.User;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    @Query("""
        SELECT u.userId
        FROM User u
        WHERE u.email = :email
        """)
    Optional<Integer> findUserIdByEmail(
            @Param("email") String email
    );
}
