package main

// Inventaire des conteneurs de serveurs, envoyé à l'API à la connexion puis
// régulièrement. L'API s'en sert pour corriger les statuts (serveur arrêté ou
// redémarré hors de son contrôle, conteneur disparu) et pour repérer les
// conteneurs orphelins, qu'elle demande de mettre en quarantaine.

import (
	"fmt"
	"log"
	"os/exec"
	"strconv"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

const (
	inventoryInterval = 5 * time.Minute
	orphanPrefix      = "mcs-orphan-"
)

type containerState struct {
	ServerID int64  `json:"server_id"`
	State    string `json:"state"`
}

// listContainerStates : conteneurs MCS (hors quarantaine) et leur état Docker
func listContainerStates() ([]containerState, error) {
	out, err := exec.Command("docker", "ps", "-a",
		"--filter", "label=mcs.managed=true",
		"--format", `{{.Names}}|{{.Label "mcs.server_id"}}|{{.State}}`).CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("docker ps : %s : %w", strings.TrimSpace(string(out)), err)
	}

	states := []containerState{}
	for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		parts := strings.Split(line, "|")
		if len(parts) != 3 || strings.HasPrefix(parts[0], orphanPrefix) {
			continue
		}
		id, err := strconv.ParseInt(parts[1], 10, 64)
		if err != nil {
			continue
		}
		states = append(states, containerState{ServerID: id, State: parts[2]})
	}
	return states, nil
}

func sendInventory(conn *websocket.Conn, fullSync bool) error {
	states, err := listContainerStates()
	if err != nil {
		return err
	}
	return writeJSON(conn, map[string]interface{}{
		"type":       "inventory",
		"full_sync":  fullSync,
		"containers": states,
	})
}

// QuarantineServer arrête un conteneur orphelin, empêche son redémarrage et le
// renomme mcs-orphan-<id>-<date>. Ses données ne sont PAS supprimées : c'est au
// volontaire ou à un admin de décider (docker rm + dossier de données).
func QuarantineServer(serverID int64) (string, error) {
	name := containerName(serverID)
	if exists, _ := containerExists(name); !exists {
		return "", fmt.Errorf("conteneur %s introuvable", name)
	}

	_ = exec.Command("docker", "stop", "-t", "30", name).Run()
	if out, err := exec.Command("docker", "update", "--restart=no", name).CombinedOutput(); err != nil {
		return "", fmt.Errorf("docker update : %s : %w", strings.TrimSpace(string(out)), err)
	}

	newName := fmt.Sprintf("%s%d-%d", orphanPrefix, serverID, time.Now().Unix())
	if out, err := exec.Command("docker", "rename", name, newName).CombinedOutput(); err != nil {
		return "", fmt.Errorf("docker rename : %s : %w", strings.TrimSpace(string(out)), err)
	}
	log.Printf("Conteneur orphelin %s mis en quarantaine sous le nom %s (données conservées)", name, newName)
	return newName, nil
}
