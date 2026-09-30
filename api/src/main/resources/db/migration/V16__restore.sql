-- Lot 31 : restauration. Pour recréer un serveur supprimé à partir de sa
-- sauvegarde, on garde son propriétaire et ses réglages (fixés à la suppression).
ALTER TABLE backups ADD COLUMN owner_id BIGINT;
ALTER TABLE backups ADD COLUMN server_name VARCHAR(64);
ALTER TABLE backups ADD COLUMN server_type VARCHAR(20);
ALTER TABLE backups ADD COLUMN minecraft_version VARCHAR(40);
ALTER TABLE backups ADD COLUMN ram_mb INTEGER;
ALTER TABLE backups ADD COLUMN cpu_cores INTEGER;
UPDATE backups b SET owner_id = s.owner_id FROM servers s WHERE s.id = b.server_tag_id;
CREATE INDEX IF NOT EXISTS idx_backups_orphans ON backups(owner_id, status) WHERE server_id IS NULL;
