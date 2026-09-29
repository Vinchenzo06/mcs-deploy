package vinch.mcs.api.entities;

/** Types de sauvegardes ; une sauvegarde peut en cumuler plusieurs */
public enum BackupKind {
    DAILY("quotidienne", "jours"),
    WEEKLY("hebdomadaire", "jours"),
    MONTHLY("mensuelle", "jours"),
    MANUAL("manuelle", "jours"),
    /** Gardée tant que le serveur existe ; la durée compte après sa suppression */
    PERMANENT("permanente", "heures");

    private final String label;
    private final String unit;

    BackupKind(String label, String unit) {
        this.label = label;
        this.unit = unit;
    }

    public String label() {
        return label;
    }

    /** Unité de la durée : jours, ou heures (permanente, après suppression du serveur) */
    public String unit() {
        return unit;
    }

    /** "quotidienne", "jour", "hebdo", "daily"... ; null si inconnu */
    public static BackupKind parse(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "quotidienne", "quotidien", "jour", "jours", "daily", "day" -> DAILY;
            case "hebdomadaire", "hebdo", "semaine", "weekly", "week" -> WEEKLY;
            case "mensuelle", "mensuel", "mois", "monthly", "month" -> MONTHLY;
            case "manuelle", "manuel", "manual" -> MANUAL;
            case "permanente", "permanent", "perma" -> PERMANENT;
            default -> null;
        };
    }
}
