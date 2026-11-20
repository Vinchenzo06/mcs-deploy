-- Lot 52 : joueur membre de la team IMG (rôle LuckPerms « img », envoyé par le proxy).
-- Sur un serveur de la team, il peut rejoindre, démarrer/arrêter, console et fichiers.
ALTER TABLE players ADD COLUMN img_member BOOLEAN NOT NULL DEFAULT FALSE;
