-- Insertion d'un volontaire de test (toi-même)
INSERT INTO volunteers (contact_email, is_active, trust_level, max_servers_allowed, max_ram_mb, registration_date)
VALUES ('vincent@example.com', TRUE, 'TRUSTED', 10, 16384, CURRENT_TIMESTAMP);

-- On insérera le node manuellement depuis le code après avoir hashé le token