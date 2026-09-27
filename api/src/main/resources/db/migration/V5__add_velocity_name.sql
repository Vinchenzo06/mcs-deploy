-- Ajoute la colonne velocity_name (unique globalement)
ALTER TABLE servers ADD COLUMN velocity_name VARCHAR(80);

-- Pour les serveurs existants (s'il y en a), on génère le velocity_name à partir de owner_id et name
UPDATE servers SET velocity_name = owner_id || '-' || name WHERE velocity_name IS NULL;

-- Maintenant on la rend NOT NULL et unique
ALTER TABLE servers ALTER COLUMN velocity_name SET NOT NULL;
ALTER TABLE servers ADD CONSTRAINT servers_velocity_name_unique UNIQUE (velocity_name);

-- Supprime la contrainte d'unicité globale sur name
ALTER TABLE servers DROP CONSTRAINT IF EXISTS servers_name_key;

-- Ajoute une contrainte d'unicité sur (owner_id, name)
ALTER TABLE servers ADD CONSTRAINT servers_owner_name_unique UNIQUE (owner_id, name);

-- Index pour les recherches rapides
CREATE INDEX IF NOT EXISTS idx_servers_velocity_name ON servers(velocity_name);
CREATE INDEX IF NOT EXISTS idx_servers_owner_name ON servers(owner_id, name);