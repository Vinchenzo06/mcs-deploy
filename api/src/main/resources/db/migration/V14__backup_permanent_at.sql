-- Lot 29b : une sauvegarde permanente ne compte plus dans les autres types, et
-- au-delà du max de permanentes, c'est la plus anciennement marquée qui cesse de
-- l'être (pas la plus ancienne sauvegarde).
ALTER TABLE backups ADD COLUMN permanent_at TIMESTAMP;
UPDATE backups SET permanent_at = COALESCE(completed_at, created_at) WHERE is_permanent;
