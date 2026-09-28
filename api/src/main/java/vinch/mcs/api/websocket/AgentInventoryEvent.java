package vinch.mcs.api.websocket;

import java.util.Map;

/**
 * Inventaire des conteneurs envoyé par un agent : server_id -> état Docker
 * ("running", "exited", "created"...).
 *
 * @param fullSync true à la connexion de l'agent (tous les statuts sont
 *                 réconciliés), false pour l'inventaire périodique (les serveurs
 *                 en cours de démarrage ou d'arrêt sont laissés tranquilles)
 */
public record AgentInventoryEvent(Long nodeId, Map<Long, String> containers, boolean fullSync) {
}
