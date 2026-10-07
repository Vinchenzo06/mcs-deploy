package vinch.mcs.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import vinch.mcs.api.entities.Server;
import vinch.mcs.api.entities.ServerType;

import java.util.List;

/**
 * Version de Java des serveurs (lot 35). Tableau d'après la doc de Paper et de l'image
 * itzg : Forge avant 1.17 exige Java 8 ; minimums de Mojang : 1.17 -> 16, 1.18 -> 17,
 * 1.20.5 -> 21, 26.x -> 25. Le propriétaire peut imposer une autre version.
 */
public final class JavaVersions {

    public static final List<Integer> AVAILABLE = List.of(8, 11, 16, 17, 21, 25);
    public static final int NEWEST = 25;

    private JavaVersions() {
    }

    /** Version de Java conseillée pour ce type et cette version de Minecraft */
    public static int auto(ServerType type, String minecraftVersion) {
        int[] v = parse(minecraftVersion);
        if (v == null || v[0] >= 26 || (v[0] == 1 && v[1] >= 22)) {
            return NEWEST; // LATEST, 26.x, instantanés : la plus récente
        }
        if (v[0] != 1) {
            return NEWEST;
        }
        int minor = v[1];
        int patch = v[2];
        boolean from1205 = minor > 20 || (minor == 20 && patch >= 5);
        ServerType t = type == null ? ServerType.PAPER : type;
        switch (t) {
            case FORGE, NEOFORGE -> {
                if (minor < 17) {
                    return 8;
                }
                if (minor == 17) {
                    return 16; // 1.17 exige Java 16 (itzg dit java8 avant 1.18 : à confirmer en test)
                }
                return from1205 ? 21 : 17;
            }
            case PAPER, SPIGOT -> {
                if (minor < 12) {
                    return 8;
                }
                if (minor < 16 || (minor == 16 && patch < 5)) {
                    return 11;
                }
                if (minor == 16) {
                    return 16;
                }
                if (minor < 20) {
                    return 17;
                }
                return 21;
            }
            default -> { // FABRIC, VANILLA
                if (minor < 17) {
                    return 8;
                }
                return from1205 ? 21 : 17;
            }
        }
    }

    /** Version effective : celle du propriétaire, sinon automatique */
    public static int effective(Server s) {
        Integer v = s.getJavaVersion();
        return v != null && AVAILABLE.contains(v) ? v : auto(s.getServerType(), s.getMinecraftVersion());
    }

    /** "8", "java 17", "21"... ; "auto" -> null */
    public static Integer parseChoice(String text) {
        String t = text == null ? "" : text.trim().toLowerCase().replace("java", "").trim();
        if (t.isEmpty() || t.equals("auto") || t.equals("automatique")) {
            return null;
        }
        try {
            int v = Integer.parseInt(t);
            if (AVAILABLE.contains(v)) {
                return v;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new RuntimeException("Version de Java inconnue : " + text + " (choisis parmi " + AVAILABLE + " ou auto)");
    }

    /** Réponse d'agent signalant un problème de version de Java */
    public static boolean isIssue(JsonNode result) {
        return result != null && "java_version".equals(result.path("error").asText());
    }

    /**
     * Version à proposer d'après le diagnostic de l'agent : la plus proche disponible
     * dans le bon sens (plus récente / plus ancienne que la version actuelle).
     */
    public static int suggest(JsonNode result, Server s, int current) {
        int needed = result.path("java_needed").asInt(0);
        String dir = result.path("java_direction").asText("");
        if (dir.isEmpty() && needed > 0) {
            dir = needed > current ? "newer" : "older";
        }
        if ("newer".equals(dir)) {
            for (int v : AVAILABLE) {
                if (v > current && v >= needed) {
                    return v;
                }
            }
            return NEWEST;
        }
        if ("older".equals(dir)) {
            int auto = auto(s.getServerType(), s.getMinecraftVersion());
            if (needed > 0) {
                int best = AVAILABLE.get(0);
                for (int v : AVAILABLE) {
                    if (v <= needed && v < current) {
                        best = v;
                    }
                }
                return best;
            }
            return auto < current ? auto : AVAILABLE.get(0);
        }
        return auto(s.getServerType(), s.getMinecraftVersion());
    }

    /** Problème de Java détecté par l'agent -> exception que le proxy sait afficher */
    public static JavaIssueException issue(JsonNode result, Server s, int current) {
        int suggested = suggest(result, s, current);
        String msg = result.path("message").asText("problème de version de Java");
        return new JavaIssueException(Character.toUpperCase(msg.charAt(0)) + msg.substring(1)
                + " (actuellement Java " + current + ").", s.getId(), s.getName(), current, suggested);
    }

    /** [major, minor, patch] de "1.20.4", "26.1.2" ; null si illisible (LATEST, instantané) */
    static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.trim().split("\\.");
        if (parts.length < 2) {
            return null;
        }
        int[] out = new int[3];
        try {
            for (int i = 0; i < 3 && i < parts.length; i++) {
                out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /** Problème de version de Java au démarrage d'un serveur */
    public static class JavaIssueException extends RuntimeException {
        private final long serverId;
        private final String serverName;
        private final int current;
        private final int suggested;

        public JavaIssueException(String message, long serverId, String serverName, int current, int suggested) {
            super(message);
            this.serverId = serverId;
            this.serverName = serverName;
            this.current = current;
            this.suggested = suggested;
        }

        public java.util.Map<String, Object> body() {
            return java.util.Map.of("error", getMessage(), "javaIssue", java.util.Map.of(
                    "serverId", serverId, "server", serverName, "current", current,
                    "suggested", suggested, "available", AVAILABLE));
        }

        public String hint() {
            return getMessage() + " Choisis une autre version : /mcs java " + serverName + " " + suggested + " start";
        }
    }
}
