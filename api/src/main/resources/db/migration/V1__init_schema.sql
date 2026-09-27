-- Activation de l'extension UUID
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Table des joueurs
CREATE TABLE players (
                         id BIGSERIAL PRIMARY KEY,
                         minecraft_uuid UUID NOT NULL UNIQUE,
                         minecraft_username VARCHAR(16) NOT NULL,
                         role VARCHAR(20) NOT NULL DEFAULT 'PLAYER',
                         max_servers INTEGER NOT NULL DEFAULT 1,
                         total_storage_mb INTEGER NOT NULL DEFAULT 5000,
                         is_banned BOOLEAN NOT NULL DEFAULT FALSE,
                         created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_players_uuid ON players(minecraft_uuid);
CREATE INDEX idx_players_username ON players(minecraft_username);

-- Table des volontaires
CREATE TABLE volunteers (
                            id BIGSERIAL PRIMARY KEY,
                            linked_player_id BIGINT REFERENCES players(id) ON DELETE SET NULL,
                            contact_email VARCHAR(255),
                            is_active BOOLEAN NOT NULL DEFAULT TRUE,
                            trust_level VARCHAR(20) NOT NULL DEFAULT 'NEW',
                            max_servers_allowed INTEGER NOT NULL DEFAULT 5,
                            max_ram_mb INTEGER NOT NULL DEFAULT 8192,
                            registration_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Table des nodes (machines des volontaires)
CREATE TABLE nodes (
                       id BIGSERIAL PRIMARY KEY,
                       volunteer_id BIGINT NOT NULL REFERENCES volunteers(id) ON DELETE CASCADE,
                       node_token_hash VARCHAR(255) NOT NULL UNIQUE,
                       region VARCHAR(50) NOT NULL,
                       hostname VARCHAR(255),
                       agent_version VARCHAR(50),
                       last_heartbeat_at TIMESTAMP,
                       is_online BOOLEAN NOT NULL DEFAULT FALSE,
                       total_ram_mb INTEGER NOT NULL DEFAULT 0,
                       used_ram_mb INTEGER NOT NULL DEFAULT 0,
                       total_storage_mb INTEGER NOT NULL DEFAULT 0,
                       used_storage_mb INTEGER NOT NULL DEFAULT 0,
                       cpu_cores INTEGER NOT NULL DEFAULT 1,
                       is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
                       created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_nodes_volunteer ON nodes(volunteer_id);
CREATE INDEX idx_nodes_region ON nodes(region);
CREATE INDEX idx_nodes_online ON nodes(is_online);

-- Table des serveurs
CREATE TABLE servers (
                         id BIGSERIAL PRIMARY KEY,
                         name VARCHAR(64) NOT NULL UNIQUE,
                         display_name VARCHAR(100) NOT NULL,
                         owner_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
                         node_id BIGINT REFERENCES nodes(id) ON DELETE SET NULL,
                         minecraft_version VARCHAR(20) NOT NULL,
                         server_type VARCHAR(20) NOT NULL,
                         allocated_ram_mb INTEGER NOT NULL DEFAULT 1024,
                         allocated_storage_mb INTEGER NOT NULL DEFAULT 5000,
                         status VARCHAR(20) NOT NULL DEFAULT 'CREATING',
                         local_port INTEGER,
                         tunnel_port INTEGER,
                         is_archived BOOLEAN NOT NULL DEFAULT FALSE,
                         created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         last_started_at TIMESTAMP,
                         last_stopped_at TIMESTAMP
);

CREATE INDEX idx_servers_owner ON servers(owner_id);
CREATE INDEX idx_servers_node ON servers(node_id);
CREATE INDEX idx_servers_status ON servers(status);
CREATE INDEX idx_servers_name ON servers(name);

-- Table des collaborateurs
CREATE TABLE server_collaborators (
                                      id BIGSERIAL PRIMARY KEY,
                                      server_id BIGINT NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
                                      player_id BIGINT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
                                      permission_level VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
                                      added_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                      UNIQUE(server_id, player_id)
);

-- Table des actions (audit log)
CREATE TABLE actions (
                         id BIGSERIAL PRIMARY KEY,
                         server_id BIGINT REFERENCES servers(id) ON DELETE SET NULL,
                         player_id BIGINT REFERENCES players(id) ON DELETE SET NULL,
                         action_type VARCHAR(30) NOT NULL,
                         status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                         details JSONB,
                         error_message TEXT,
                         created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         completed_at TIMESTAMP
);

CREATE INDEX idx_actions_server ON actions(server_id);
CREATE INDEX idx_actions_player ON actions(player_id);
CREATE INDEX idx_actions_created ON actions(created_at);

-- Table des backups
CREATE TABLE backups (
                         id BIGSERIAL PRIMARY KEY,
                         server_id BIGINT NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
                         backup_type VARCHAR(20) NOT NULL DEFAULT 'AUTO',
                         storage_path VARCHAR(500) NOT NULL,
                         size_mb INTEGER NOT NULL DEFAULT 0,
                         is_complete BOOLEAN NOT NULL DEFAULT FALSE,
                         created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         completed_at TIMESTAMP
);

CREATE INDEX idx_backups_server ON backups(server_id);
CREATE INDEX idx_backups_created ON backups(created_at);