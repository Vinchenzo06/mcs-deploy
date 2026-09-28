package vinch.mcs.api.websocket;

/**
 * Événement Docker en direct transmis par un agent pour un serveur.
 *
 * @param event    "start", "die", "healthy" (Minecraft répond) ou "unhealthy"
 * @param exitCode code de sortie pour "die" (null sinon)
 */
public record AgentServerEvent(Long nodeId, Long serverId, String event, Integer exitCode) {
}
