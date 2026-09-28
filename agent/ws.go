package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// gorilla/websocket n'accepte qu'un seul écrivain à la fois : heartbeats,
// inventaires et résultats de commandes passent tous par writeJSON.
var writeMu sync.Mutex

func writeJSON(conn *websocket.Conn, v interface{}) error {
	writeMu.Lock()
	defer writeMu.Unlock()
	return conn.WriteJSON(v)
}

// errAuth : l'API a refusé le jeton du node
var errAuth = errors.New("authentification refusée par l'API")

type Message struct {
	Type string                 `json:"type"`
	Data map[string]interface{} `json:"data,omitempty"`
}

type RegisterMessage struct {
	Type         string   `json:"type"`
	NodeToken    string   `json:"node_token"`
	AgentVersion string   `json:"agent_version"`
	Capacity     Capacity `json:"capacity"`
	Host         HostInfo `json:"host"`
}

type HeartbeatMessage struct {
	Type  string                 `json:"type"`
	Stats map[string]interface{} `json:"stats"`
}

func runConnection(config *Config, stopChan chan struct{}, events <-chan serverEvent) error {
	conn, _, err := websocket.DefaultDialer.Dial(config.API.URL, nil)
	if err != nil {
		return err
	}
	defer conn.Close()

	log.Println("WebSocket connecté")

	host := hostInfo(config.Docker.DataPath)
	host.DataUsedMB = dataUsedMB(config.Docker.DataPath)
	capacity := effectiveCapacity(config.Capacity, host)
	register := RegisterMessage{
		Type:         "register",
		NodeToken:    config.Node.Token,
		AgentVersion: config.Node.AgentVersion,
		Capacity:     capacity,
		Host:         host,
	}
	log.Printf("Capacité prêtée : %d Mo de RAM, %d cœur(s), %d Mo de disque", capacity.RAMMB, capacity.CPUCores, capacity.DiskMB)

	if err := writeJSON(conn, register); err != nil {
		return err
	}

	log.Println("Authentification envoyée, attente de la réponse...")

	// On lit le message comme une map pour accéder à toutes les clés
	_, rawMessage, err := conn.ReadMessage()
	if err != nil {
		return err
	}

	var response map[string]interface{}
	if err := json.Unmarshal(rawMessage, &response); err != nil {
		return err
	}

	respType, _ := response["type"].(string)

	if respType == "error" {
		return fmt.Errorf("%w : %v", errAuth, response)
	}

	if respType != "register_ok" {
		return fmt.Errorf("réponse inattendue à l'authentification : %s", respType)
	}

	nodeID, _ := response["node_id"].(float64)
	log.Printf("Authentifié avec succès, node_id : %d", int64(nodeID))

	done := make(chan struct{})

	go func() {
		defer close(done)
		for {
			var msg map[string]interface{}
			if err := conn.ReadJSON(&msg); err != nil {
				log.Printf("Erreur de lecture : %v", err)
				return
			}

			msgType, _ := msg["type"].(string)
			commandId, _ := msg["command_id"].(string)

			// Tout ce qui n'est pas heartbeat ou register est une commande. Chaque
			// commande tourne à part : une création de plusieurs minutes ne bloque
			// pas les autres (arrêt d'un autre serveur, quarantaine...).
			go handleCommand(conn, config, msgType, commandId, msg)
		}
	}()

	// Événements accumulés pendant la déconnexion : périmés, l'inventaire complet
	// envoyé juste après les remplace
	for drained := false; !drained; {
		select {
		case <-events:
		default:
			drained = true
		}
	}

	// Inventaire complet à la connexion : l'API réconcilie tous les statuts
	if err := sendInventory(conn, true); err != nil {
		log.Printf("Inventaire : %v", err)
	}

	heartbeatTicker := time.NewTicker(time.Duration(config.Heartbeat.IntervalSeconds) * time.Second)
	defer heartbeatTicker.Stop()
	inventoryTicker := time.NewTicker(inventoryInterval)
	defer inventoryTicker.Stop()
	statsTicker := time.NewTicker(statsInterval)
	defer statsTicker.Stop()

	go sendStats(conn, config.Docker.DataPath)

	for {
		select {
		case <-done:
			return nil
		case <-heartbeatTicker.C:
			if err := sendHeartbeat(conn, config.Docker.DataPath); err != nil {
				return err
			}
		case ev := <-events:
			msg := map[string]interface{}{"type": "server_event", "server_id": ev.ServerID, "event": ev.Event}
			if ev.ExitCode != nil {
				msg["exit_code"] = *ev.ExitCode
			}
			if err := writeJSON(conn, msg); err != nil {
				return err
			}
		case <-statsTicker.C:
			go sendStats(conn, config.Docker.DataPath)
		case <-inventoryTicker.C:
			if err := sendInventory(conn, false); err != nil {
				log.Printf("Inventaire : %v", err)
			}
		case <-stopChan:
			log.Println("Arrêt demandé, déconnexion...")
			writeMu.Lock()
			_ = conn.WriteMessage(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""))
			writeMu.Unlock()
			return nil
		}
	}
}

func sendHeartbeat(conn *websocket.Conn, dataPath string) error {
	host := hostInfo(dataPath)
	heartbeat := HeartbeatMessage{
		Type: "heartbeat",
		Stats: map[string]interface{}{
			"ram_used_mb":      host.RAMTotalMB - host.RAMAvailMB,
			"ram_available_mb": host.RAMAvailMB,
			"disk_free_mb":     host.DiskFreeMB,
			"disk_total_mb":    host.DiskTotalMB,
		},
	}
	return writeJSON(conn, heartbeat)
}
