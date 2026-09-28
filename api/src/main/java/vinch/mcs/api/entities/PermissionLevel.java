package vinch.mcs.api.entities;

/** Rôle d'un joueur invité sur un serveur (le propriétaire n'en a pas besoin) */
public enum PermissionLevel {
    /** Rejoindre le serveur */
    MEMBER("membre"),
    /** + démarrer, arrêter, redémarrer */
    OPERATOR("gerant"),
    /** + console et fichiers */
    TECHNICIAN("technicien");

    private final String label;

    PermissionLevel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** "membre", "gerant"/"gérant", "technicien" (ou le nom anglais) ; null si inconnu */
    public static PermissionLevel parse(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().toLowerCase(java.util.Locale.ROOT).replace('é', 'e');
        for (PermissionLevel l : values()) {
            if (l.label.equals(v) || l.name().equalsIgnoreCase(v)) {
                return l;
            }
        }
        return null;
    }
}
