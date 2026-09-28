-- Ressources réelles de la machine, remontées par l'agent (connexion + heartbeat).
-- La capacité prêtée par le volontaire utilise les colonnes existantes
-- total_ram_mb, cpu_cores et total_storage_mb.
ALTER TABLE nodes ADD COLUMN host_ram_mb INTEGER;
ALTER TABLE nodes ADD COLUMN host_cpu_cores INTEGER;
ALTER TABLE nodes ADD COLUMN host_disk_total_mb INTEGER;
ALTER TABLE nodes ADD COLUMN host_disk_free_mb INTEGER;
