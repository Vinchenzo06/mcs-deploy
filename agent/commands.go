package main

import (
	"encoding/json"
	"log"
	"time"

	"github.com/gorilla/websocket"
)

// handleCommand dispatch les commandes reçues vers le bon handler
func handleCommand(conn *websocket.Conn, config *Config, msgType, commandId string, msg map[string]interface{}) {
	log.Printf("Commande reçue : type=%s command_id=%s", msgType, commandId)

	switch msgType {
	case "ping":
		handlePing(conn, commandId, msg)
	case "create_server":
		handleCreateServer(conn, config, commandId, msg)
	case "start_server":
		handleStartServer(conn, config, commandId, msg)
	case "stop_server":
		handleStopServer(conn, commandId, msg)
	case "delete_server":
		handleDeleteServer(conn, config, commandId, msg)
	case "server_status":
		handleServerStatus(conn, commandId, msg)
	case "list_servers":
		handleListServers(conn, commandId)
	case "quarantine_server":
		handleQuarantineServer(conn, commandId, msg)
	case "console":
		handleConsoleCommand(conn, commandId, msg)
	case "backup_server":
		handleBackupServer(conn, config, commandId, msg)
	default:
		log.Printf("Type de commande non géré : %s", msgType)
		sendCommandError(conn, commandId, "unknown_command", "Commande inconnue : "+msgType)
	}
}

// handlePing reste tel quel
func handlePing(conn *websocket.Conn, commandId string, msg map[string]interface{}) {
	var receivedTimestamp int64
	if data, ok := msg["data"].(map[string]interface{}); ok {
		if ts, ok := data["timestamp"].(float64); ok {
			receivedTimestamp = int64(ts)
		}
	}

	now := time.Now().UnixMilli()
	latencyMs := now - receivedTimestamp

	sendCommandResult(conn, commandId, map[string]interface{}{
		"pong":            true,
		"agent_timestamp": now,
		"latency_ms":      latencyMs,
	})

	log.Printf("Pong envoyé (latence : %dms)", latencyMs)
}

// handleCreateServer crée un nouveau serveur Minecraft
func handleCreateServer(conn *websocket.Conn, config *Config, commandId string, msg map[string]interface{}) {
	data, ok := msg["data"].(map[string]interface{})
	if !ok {
		sendCommandError(conn, commandId, "missing_data", "data manquant")
		return
	}

	// Parser la requête à partir de la map
	raw, _ := json.Marshal(data)
	var req CreateServerRequest
	if err := json.Unmarshal(raw, &req); err != nil {
		sendCommandError(conn, commandId, "invalid_data", err.Error())
		return
	}

	if err := CreateServer(config.Docker.DataPath, req); err != nil {
		sendCommandError(conn, commandId, "create_failed", err.Error())
		return
	}

	sendCommandResult(conn, commandId, map[string]interface{}{
		"server_id": req.ServerID,
		"status":    "created",
		"port":      req.Port,
	})
}

func handleStartServer(conn *websocket.Conn, config *Config, commandId string, msg map[string]interface{}) {
	serverID, ok := getServerID(msg)
	if !ok {
		sendCommandError(conn, commandId, "missing_server_id", "server_id manquant")
		return
	}

	// Serveurs créés avant la 0.9.0 : les commandes console de MCS ne doivent pas
	// s'afficher aux OP ("[Rcon: ...]"). Pris en compte à ce démarrage.
	if err := setServerProperty(serverDataPath(config.Docker.DataPath, serverID), "broadcast-rcon-to-ops", "false"); err != nil {
		log.Printf("server.properties du serveur %d : %v", serverID, err)
	}

	if err := StartServer(serverID); err != nil {
		sendCommandError(conn, commandId, "start_failed", err.Error())
		return
	}

	sendCommandResult(conn, commandId, map[string]interface{}{
		"server_id": serverID,
		"status":    "started",
	})
}

func handleStopServer(conn *websocket.Conn, commandId string, msg map[string]interface{}) {
	serverID, ok := getServerID(msg)
	if !ok {
		sendCommandError(conn, commandId, "missing_server_id", "server_id manquant")
		return
	}

	if err := StopServer(serverID); err != nil {
		sendCommandError(conn, commandId, "stop_failed", err.Error())
		return
	}

	sendCommandResult(conn, commandId, map[string]interface{}{
		"server_id": serverID,
		"status":    "stopped",
	})
}

func handleDeleteServer(conn *websocket.Conn, config *Config, commandId string, msg map[string]interface{}) {
	serverID, ok := getServerID(msg)
	if !ok {
		sendCommandError(conn, commandId, "missing_server_id", "server_id manquant")
		return
	}

	deleteData := false
	if data, ok := msg["data"].(map[string]interface{}); ok {
		if dd, ok := data["delete_data"].(bool); ok {
			deleteData = dd
		}
	}

	if err := DeleteServer(config.Docker.DataPath, serverID, deleteData); err != nil {
		sendCommandError(conn, commandId, "delete_failed", err.Error())
		return
	}

	sendCommandResult(conn, commandId, map[string]interface{}{
		"server_id": serverID,
		"status":    "deleted",
	})
}

func handleServerStatus(conn *websocket.Conn, commandId string, msg map[string]interface{}) {
	serverID, ok := getServerID(msg)
	if !ok {
		sendCommandError(conn, commandId, "missing_server_id", "server_id manquant")
		return
	}

	status, err := GetServerStatus(serverID)
	if err != nil {
		sendCommandError(conn, commandId, "status_failed", err.Error())
		return
	}

	sendCommandResult(conn, commandId, status)
}

func handleListServers(conn *websocket.Conn, commandId string) {
	containers, err := ListMcsContainers()
	if err != nil {
		sendCommandError(conn, commandId, "list_failed", err.Error())
		return
	}

	sendCommandResult(conn, commandId, map[string]interface{}{
		"containers": containers,
	})
}

// handleQuarantineServer isole un conteneur orphelin (inconnu de l'API)
func handleQuarantineServer(conn *websocket.Conn, commandId string, msg map[string]interface{}) {
	serverID, ok := getServerID(msg)
	if !ok {
		sendCommandError(conn, commandId, "missing_server_id", "server_id manquant")
		return
	}

	newName, err := QuarantineServer(serverID)
	if err != nil {
		sendCommandError(conn, commandId, "quarantine_failed", err.Error())
		return
	}

	sendCommandResult(conn, commandId, map[string]interface{}{
		"server_id": serverID,
		"status":    "quarantined",
		"container": newName,
	})
}

// getServerID extrait le server_id depuis le payload data
func getServerID(msg map[string]interface{}) (int64, bool) {
	data, ok := msg["data"].(map[string]interface{})
	if !ok {
		return 0, false
	}
	id, ok := data["server_id"].(float64)
	if !ok {
		return 0, false
	}
	return int64(id), true
}

// sendCommandResult envoie le résultat d'une commande
func sendCommandResult(conn *websocket.Conn, commandId string, result interface{}) {
	response := map[string]interface{}{
		"type":       "command_result",
		"command_id": commandId,
		"success":    true,
		"result":     result,
	}

	if err := writeJSON(conn, response); err != nil {
		log.Printf("Erreur d'envoi du résultat : %v", err)
	}
}

// sendCommandError envoie une erreur de commande
func sendCommandError(conn *websocket.Conn, commandId, errorCode, errorMsg string) {
	response := map[string]interface{}{
		"type":       "command_result",
		"command_id": commandId,
		"success":    false,
		"error":      errorCode,
		"message":    errorMsg,
	}

	if err := writeJSON(conn, response); err != nil {
		log.Printf("Erreur d'envoi de l'erreur : %v", err)
	}
}
