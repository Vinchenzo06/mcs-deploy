-- Supprime les indexes liés à is_archived (créés en V3)
DROP INDEX IF EXISTS idx_servers_tunnel_port;
DROP INDEX IF EXISTS idx_servers_local_port;
DROP INDEX IF EXISTS idx_servers_owner;
DROP INDEX IF EXISTS idx_servers_node;

-- Supprime la colonne is_archived
ALTER TABLE servers DROP COLUMN IF EXISTS is_archived;

-- Recrée les indexes sans le filtre is_archived
CREATE INDEX IF NOT EXISTS idx_servers_tunnel_port ON servers(tunnel_port);
CREATE INDEX IF NOT EXISTS idx_servers_local_port ON servers(local_port);
CREATE INDEX IF NOT EXISTS idx_servers_owner ON servers(owner_id);
CREATE INDEX IF NOT EXISTS idx_servers_node ON servers(node_id);