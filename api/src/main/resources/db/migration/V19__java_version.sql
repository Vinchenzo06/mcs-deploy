-- Lot 35 : version de Java choisie pour un serveur (8, 11, 16, 17, 21, 25).
-- NULL = automatique selon le type et la version de Minecraft.
ALTER TABLE servers ADD COLUMN java_version INTEGER;
