-- Lot 46 : serveur de la team IMG (sudo mcs-team) : reçoit les plugins de la team
-- (img-farm…) à chaque démarrage, et ils sont retirés quand il n'en fait plus partie.
ALTER TABLE servers ADD COLUMN is_team BOOLEAN NOT NULL DEFAULT FALSE;
