-- Index pour la recherche rapide de port libre
CREATE INDEX IF NOT EXISTS idx_servers_tunnel_port ON servers(tunnel_port) WHERE is_archived = FALSE;
CREATE INDEX IF NOT EXISTS idx_servers_local_port ON servers(local_port) WHERE is_archived = FALSE;

-- Index pour la recherche par owner
CREATE INDEX IF NOT EXISTS idx_servers_owner ON servers(owner_id) WHERE is_archived = FALSE;

-- Index pour la recherche par node
CREATE INDEX IF NOT EXISTS idx_servers_node ON servers(node_id) WHERE is_archived = FALSE;