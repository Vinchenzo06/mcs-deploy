package vinch.mcs.api.entities;

public enum BackupType {
    AUTO,
    MANUAL,
    PRE_MIGRATION,
    PRE_DELETE,
    /** Rangement à l'arrêt (lot 33) : caché au propriétaire */
    PARK
}