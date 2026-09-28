-- Lot 17 : accès aux serveurs
-- Serveur privé par défaut : seuls le propriétaire, ses invités, l'hébergeur et
-- les admins peuvent y entrer (contrôlé par le proxy)
ALTER TABLE servers ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT FALSE;

-- Joueur propriétaire de la machine (volontaire) : peut rejoindre, démarrer,
-- arrêter, utiliser la console des serveurs hébergés chez lui
ALTER TABLE nodes ADD COLUMN owner_player_id BIGINT REFERENCES players(id) ON DELETE SET NULL;

-- Rôles des invités : MEMBER (rejoindre), OPERATOR (+ démarrer/arrêter),
-- TECHNICIAN (+ console et fichiers)
UPDATE server_collaborators SET permission_level = 'MEMBER'
 WHERE permission_level NOT IN ('MEMBER', 'OPERATOR', 'TECHNICIAN');
CREATE INDEX IF NOT EXISTS idx_server_collaborators_player ON server_collaborators(player_id);
