-- Lot 41 : serveur protégé (ex. le lobby IMG) : jamais supprimé, ni à la main
-- ni après 6 mois sans démarrage. Réglé sur le VPS avec sudo mcs-protect.
ALTER TABLE servers ADD COLUMN is_protected BOOLEAN NOT NULL DEFAULT FALSE;
