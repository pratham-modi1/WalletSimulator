-- 1. Create the ADMIN user
INSERT INTO `User` (
    email,
    password_hash,
    role,
    user_status
)
VALUES (
    'admin@aureus.com',
    '$2a$10$S6YuLxlzCPoZHREWiwrudO2BHkGFRKjgJjjEN8wqv.uWkTd/96o6q',
    'ADMIN',
    'ACTIVE'
);



-- 2. Create the SYSTEM account owned by the ADMIN
INSERT INTO Account (
    user_id,
    account_type,
    balance
)
VALUES (
    (SELECT user_id FROM `User` WHERE email = 'admin@aureus.com'),
    'SYSTEM',
    99999999.99
);
