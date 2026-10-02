-- Lot 33 : rangement des serveurs arrêtés au central.
--  park_state : DIRTY (la copie sur la machine est plus récente que le rangement :
--               il a tourné depuis), CLEAN (copie de la machine = rangement),
--               COLD (plus de copie sur la machine, seulement au central),
--               LOST (machine hors ligne depuis longtemps avec des données non rangées :
--               il repartira de sa dernière sauvegarde)
--  parked_backup_id : sauvegarde de rangement (cachée au propriétaire)
ALTER TABLE servers ADD COLUMN park_state VARCHAR(10) NOT NULL DEFAULT 'DIRTY';
ALTER TABLE servers ADD COLUMN parked_backup_id BIGINT;
ALTER TABLE servers ADD COLUMN inactivity_warned_at TIMESTAMP;
ALTER TABLE nodes ADD COLUMN offline_since TIMESTAMP;

-- Plus de sauvegardes sur les machines des volontaires
UPDATE nodes SET accepts_local_backups = FALSE;
UPDATE backups SET status = 'DELETED', message = 'Sauvegardes sur les machines retirées'
WHERE location = 'LOCAL' AND status IN ('SUCCESS', 'EXPIRED', 'RUNNING');
DELETE FROM backup_policies WHERE scope LIKE 'local:%';
-- Réglages par rôle : plus de réglages propres aux serveurs posés par les propriétaires
-- (les admins pourront en refaire)
DELETE FROM backup_policies WHERE scope LIKE 'server:%' AND (updated_by IS NULL OR updated_by NOT IN
    (SELECT minecraft_username FROM players WHERE role = 'ADMIN'));
