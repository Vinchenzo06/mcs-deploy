package vinch.mcs.api.entities;

public enum ServerStatus {
    CREATING,
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
    MIGRATING,
    DELETED
}