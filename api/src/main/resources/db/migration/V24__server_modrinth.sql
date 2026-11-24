-- Lot 70 : plugins ou mods Modrinth d'un serveur (slugs ou identifiants séparés par des virgules).
-- L'image itzg télécharge à chaque démarrage la dernière version compatible avec la version de
-- Minecraft et le type du serveur ; un projet sans version compatible est ignoré (« ? »).
ALTER TABLE servers ADD COLUMN modrinth_projects TEXT;
