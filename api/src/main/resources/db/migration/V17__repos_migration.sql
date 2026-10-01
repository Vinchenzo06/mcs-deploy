-- Lot 32 : un dépôt restic par serveur au central ("srv<id>"), pour qu'un serveur
-- (et toutes ses sauvegardes) puisse changer de machine. Les anciens dépôts par
-- machine ("node<id>") restent lisibles par leur machine seulement ; le serveur de
-- sauvegarde recopie leurs instantanés dans les dépôts des serveurs (restic copy).
CREATE TABLE backup_repos (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(40) NOT NULL UNIQUE,
    http_password VARCHAR(64) NOT NULL,
    repo_password VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO backup_repos (name, http_password, repo_password)
SELECT 'node' || id, backup_http_password, backup_repo_password FROM nodes
WHERE backup_http_password IS NOT NULL AND backup_repo_password IS NOT NULL;

-- Instantané recopié depuis un ancien dépôt de machine : l'original y sera supprimé
ALTER TABLE backups ADD COLUMN legacy_repo VARCHAR(40);
ALTER TABLE backups ADD COLUMN legacy_snapshot VARCHAR(80);

-- Serveurs déplacés pendant que leur ancienne machine était hors ligne : à
-- supprimer de cette machine quand elle revient
CREATE TABLE pending_deletions (
    id BIGSERIAL PRIMARY KEY,
    node_id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (node_id, server_id)
);

-- Messages à montrer aux joueurs (serveur déplacé, machine hors ligne...)
CREATE TABLE player_notifications (
    id BIGSERIAL PRIMARY KEY,
    player_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    message VARCHAR(600) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP
);
CREATE INDEX idx_notifications_player ON player_notifications(player_id, delivered_at);

-- Régions : "default" devient "ca-est" (la seule machine actuelle, au Québec)
UPDATE nodes SET region = 'ca-est' WHERE region = 'default';
