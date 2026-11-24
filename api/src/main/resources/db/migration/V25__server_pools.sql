-- Lot 76 : serveurs « à la demande » (speedrun, farm sur un monde neuf…). Un serveur d'un groupe
-- (pool) est créé par MCS depuis un modèle (deploy/pools) ; il ne compte dans le quota de
-- personne. last_joined_at : dernière entrée d'un joueur (les serveurs en trop sont supprimés
-- après un jour sans personne).
ALTER TABLE servers ADD COLUMN pool VARCHAR(32);
ALTER TABLE servers ADD COLUMN last_joined_at TIMESTAMP;
CREATE INDEX idx_servers_pool ON servers (pool);
