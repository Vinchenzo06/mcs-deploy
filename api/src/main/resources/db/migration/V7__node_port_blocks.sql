-- Chaque machine volontaire reçoit sa propre plage de ports, servie par des
-- tunnels rathole à son propre jeton : une machine ne peut plus réclamer les
-- ports (donc les serveurs) d'une autre.
ALTER TABLE nodes ADD COLUMN port_start INTEGER;
ALTER TABLE nodes ADD COLUMN port_end INTEGER;
ALTER TABLE nodes ADD CONSTRAINT nodes_port_range_valid
    CHECK (port_start IS NULL OR (port_end IS NOT NULL AND port_end >= port_start));

-- Les machines jumelées avant ce changement n'ont pas de plage :
-- elles sont révoquées et doivent être jumelées à nouveau (sudo mcs-add-node).
UPDATE nodes SET is_revoked = TRUE, is_online = FALSE WHERE port_start IS NULL;
