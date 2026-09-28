-- Lot 19 : rôle réseau affiché sur les serveurs de jeu
-- Groupe principal LuckPerms du joueur et son préfixe (composant texte JSON)
ALTER TABLE players ADD COLUMN network_rank VARCHAR(32);
ALTER TABLE players ADD COLUMN network_prefix VARCHAR(1024);

-- Le propriétaire peut couper l'affichage (équipes vanilla) sur son serveur
ALTER TABLE servers ADD COLUMN show_network_rank BOOLEAN NOT NULL DEFAULT TRUE;
