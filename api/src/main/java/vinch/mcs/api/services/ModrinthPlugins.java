package vinch.mcs.api.services;

import vinch.mcs.api.entities.Server;
import vinch.mcs.api.entities.ServerType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Plugins ou mods Modrinth d'un serveur (lot 70). L'API envoie la liste à l'agent avec chaque
 * démarrage ({@code modrinth_projects}) ; l'agent la passe à l'image itzg (MODRINTH_PROJECTS),
 * qui télécharge la dernière version compatible avec la version de Minecraft et le type du
 * serveur, retire les fichiers des projets enlevés de la liste, et ignore un projet sans version
 * compatible au lieu d'empêcher le démarrage.
 */
public final class ModrinthPlugins {

    /** Slug ou identifiant Modrinth (lettres, chiffres, - et _) */
    private static final Pattern SLUG = Pattern.compile("^[A-Za-z0-9_-]{2,64}$");
    public static final int MAX = 40;

    private ModrinthPlugins() {
    }

    /** Types qui chargent des plugins (Paper, Spigot) ou des mods (Fabric, Forge, NeoForge) */
    public static boolean supports(ServerType type) {
        return type != null && type != ServerType.VANILLA;
    }

    public static String kind(ServerType type) {
        return type == ServerType.PAPER || type == ServerType.SPIGOT ? "plugins" : "mods";
    }

    public static List<String> list(Server server) {
        String raw = server.getModrinthProjects();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    /** Liste nettoyée (doublons retirés, ordre gardé) ; exception si un nom est invalide */
    public static List<String> normalize(List<String> projects) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : projects == null ? List.<String>of() : projects) {
            String x = p == null ? "" : p.trim();
            if (x.isEmpty()) {
                continue;
            }
            if (!SLUG.matcher(x).matches()) {
                throw new IllegalArgumentException("Nom de projet Modrinth invalide : " + x);
            }
            out.add(x);
        }
        if (out.size() > MAX) {
            throw new IllegalArgumentException("Au plus " + MAX + " projets Modrinth par serveur.");
        }
        return new ArrayList<>(out);
    }

    public static void apply(Map<String, Object> data, Server server) {
        data.put("modrinth_projects", supports(server.getServerType()) ? list(server) : List.of());
    }
}
