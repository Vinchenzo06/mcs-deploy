package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"runtime"
	"time"

	"github.com/gorilla/websocket"
)

// errAuth : l'API a refusé le jeton du node
var errAuth = errors.New("authentification refusée par l'API")

type Message struct {
	Type string                 `json:"type"`
	Data map[string]interface{} `json:"data,omitempty"`
}

type RegisterMessage struct {
	Type         string `json:"type"`
	NodeToken    string `json:"node_token"`
	AgentVersion string `json:"agent_version"`
}

type HeartbeatMessage struct {
	Type  string                 `json:"type"`
	Stats map[string]interface{} `json:"stats"`
}

func runConnection(config *Config, stopChan chan struct{}) error {
	conn, _, err := websocket.DefaultDialer.Dial(config.API.URL, nil)
	if err != nil {
		return err
	}
	defer conn.Close()

	log.Println("WebSocket connecté")

	register := RegisterMessage{
		Type:         "register",
		NodeToken:    config.Node.Token,
		AgentVersion: config.Node.AgentVersion,
	}

	if err := conn.WriteJSON(register); err != nil {
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

			// Tout ce qui n'est pas heartbeat ou register est une commande
			handleCommand(conn, config, msgType, commandId, msg)
		}
	}()

	heartbeatTicker := time.NewTicker(time.Duration(config.Heartbeat.IntervalSeconds) * time.Second)
	defer heartbeatTicker.Stop()

	for {
		select {
		case <-done:
			return nil
		case <-heartbeatTicker.C:
			if err := sendHeartbeat(conn); err != nil {
				return err
			}
		case <-stopChan:
			log.Println("Arrêt demandé, déconnexion...")
			conn.WriteMessage(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""))
			return nil
		}
	}
}

func sendHeartbeat(conn *websocket.Conn) error {
	var memStats runtime.MemStats
	runtime.ReadMemStats(&memStats)

	heartbeat := HeartbeatMessage{
		Type: "heartbeat",
		Stats: map[string]interface{}{
			"ram_used_mb":     int(memStats.Alloc / 1024 / 1024),
			"storage_used_mb": 0,
		},
	}

	if err := conn.WriteJSON(heartbeat); err != nil {
		return err
	}

	log.Println("Heartbeat envoyé")
	return nil
}
