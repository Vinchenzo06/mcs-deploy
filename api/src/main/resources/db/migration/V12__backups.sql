-- Lot 26 : sauvegardes (restic, serveur de sauvegarde à la maison via le VPS)

-- Identifiants de chaque machine auprès du serveur de sauvegarde (rest-server,
-- dépôt "node<id>" en ajout seul) et mot de passe de chiffrement de son dépôt
ALTER TABLE nodes ADD COLUMN backup_http_password VARCHAR(64);
ALTER TABLE nodes ADD COLUMN backup_repo_password VARCHAR(64);

-- Politique : réglage du serveur (admins) > méta LuckPerms du propriétaire > défaut
ALTER TABLE servers ADD COLUMN backup_interval_hours INTEGER;
ALTER TABLE servers ADD COLUMN backup_keep_last INTEGER;
ALTER TABLE servers ADD COLUMN backup_keep_weekly INTEGER;
ALTER TABLE players ADD COLUMN backup_interval_hours INTEGER;
ALTER TABLE players ADD COLUMN backup_keep_last INTEGER;
ALTER TABLE players ADD COLUMN backup_keep_weekly INTEGER;

-- Suivi de chaque sauvegarde
ALTER TABLE backups ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS';
ALTER TABLE backups ADD COLUMN snapshot_id VARCHAR(80);
ALTER TABLE backups ADD COLUMN total_mb INTEGER;
ALTER TABLE backups ADD COLUMN message VARCHAR(500);
ALTER TABLE backups ADD COLUMN node_id BIGINT;
ALTER TABLE backups ADD COLUMN requested_by VARCHAR(16);
CREATE INDEX IF NOT EXISTS idx_backups_server_status ON backups(server_id, status, created_at);
