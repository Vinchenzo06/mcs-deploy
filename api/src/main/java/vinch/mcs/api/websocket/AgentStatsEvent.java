package vinch.mcs.api.websocket;

import com.fasterxml.jackson.databind.JsonNode;

/** Mesures périodiques (CPU, RAM, réseau, disque) des serveurs d'une machine. */
public record AgentStatsEvent(Long nodeId, JsonNode servers) {
}
