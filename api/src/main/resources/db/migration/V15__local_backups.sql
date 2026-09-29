-- Lot 30 : sauvegardes gardées sur la machine qui héberge le serveur (si son
-- volontaire l'accepte), comptées dans le quota disque du serveur.
ALTER TABLE backups ADD COLUMN location VARCHAR(10) NOT NULL DEFAULT 'CENTRAL';
ALTER TABLE nodes ADD COLUMN accepts_local_backups BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE servers ADD COLUMN local_backup_password VARCHAR(64);
ALTER TABLE servers ADD COLUMN local_backup_mb INTEGER;

-- Minimum toujours gardé au central, même si le propriétaire baisse ses réglages :
-- 2 hebdomadaires gardées 14 jours
INSERT INTO backup_policies (scope, weekly_max, weekly_days)
VALUES ('min', 2, 14)
ON CONFLICT (scope) DO NOTHING;

-- Sur les machines qui acceptent : minimum que l'hôte doit offrir (et défaut) :
-- 3 quotidiennes gardées 3 jours, 2 manuelles gardées 3 jours
INSERT INTO backup_policies (scope, daily_max, daily_days, manual_max, manual_days)
VALUES ('local:network', 3, 3, 2, 3)
ON CONFLICT (scope) DO NOTHING;
