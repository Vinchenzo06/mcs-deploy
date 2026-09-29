-- Lot 29 : types de sauvegardes (quotidienne, hebdomadaire, mensuelle, manuelle,
-- permanente), chacun avec un nombre max et une durée de vie. L'API décide seule
-- de ce qui expire ; le serveur de sauvegarde supprime ce qu'elle lui indique.

-- Une sauvegarde peut compter pour plusieurs types (la quotidienne du lundi est
-- aussi l'hebdomadaire) ; elle expire quand plus aucun type ne la retient.
ALTER TABLE backups ADD COLUMN is_daily BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE backups ADD COLUMN is_weekly BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE backups ADD COLUMN is_monthly BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE backups ADD COLUMN is_manual BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE backups ADD COLUMN is_permanent BOOLEAN NOT NULL DEFAULT FALSE;
-- Les sauvegardes survivent à la suppression du serveur (le temps de leur durée de vie)
ALTER TABLE backups ADD COLUMN server_tag_id BIGINT;
ALTER TABLE backups ADD COLUMN server_ref VARCHAR(80);
ALTER TABLE backups ADD COLUMN expires_at TIMESTAMP;
ALTER TABLE backups ALTER COLUMN server_id DROP NOT NULL;
ALTER TABLE backups DROP CONSTRAINT IF EXISTS backups_server_id_fkey;
ALTER TABLE backups ADD CONSTRAINT backups_server_id_fkey
    FOREIGN KEY (server_id) REFERENCES servers(id) ON DELETE SET NULL;

-- Sauvegardes du lot 26 : automatiques -> quotidiennes, manuelles -> manuelles
UPDATE backups SET is_manual = (backup_type = 'MANUAL'),
                   is_daily = (backup_type <> 'MANUAL'),
                   server_tag_id = server_id;
UPDATE backups b SET server_ref = s.velocity_name FROM servers s WHERE s.id = b.server_id;
CREATE INDEX IF NOT EXISTS idx_backups_tag ON backups(server_tag_id, status, created_at);

-- Réglages : 'network' (défauts du réseau), 'rank:<groupe>' (limites du rôle pour
-- les propriétaires), 'server:<id>' (réglages d'un serveur). Null : valeur du réseau.
-- *_days : durée de vie en jours ; permanent_hours : durée gardée après la
-- suppression du serveur (0 : supprimée tout de suite).
CREATE TABLE backup_policies (
    id BIGSERIAL PRIMARY KEY,
    scope VARCHAR(60) NOT NULL UNIQUE,
    daily_max INTEGER,
    daily_days INTEGER,
    weekly_max INTEGER,
    weekly_days INTEGER,
    monthly_max INTEGER,
    monthly_days INTEGER,
    manual_max INTEGER,
    manual_days INTEGER,
    permanent_max INTEGER,
    permanent_hours INTEGER,
    updated_by VARCHAR(16),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Défauts voulus par Vincent : 1 par jour gardée 3 jours, 1 par semaine gardée
-- 2 semaines, pas de mensuelle, 2 manuelles gardées 3 jours, 1 permanente gardée
-- 24 h après la suppression du serveur
INSERT INTO backup_policies (scope, daily_max, daily_days, weekly_max, weekly_days, monthly_max, monthly_days,
                             manual_max, manual_days, permanent_max, permanent_hours)
VALUES ('network', 3, 3, 2, 14, 0, 30, 2, 3, 1, 24);
