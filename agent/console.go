package main

// Console : exécute une commande Minecraft dans un serveur via rcon-cli, fourni
// par l'image itzg (RCON activé par défaut, mot de passe généré dans le conteneur,
// port jamais publié). Utilisé par /mcs console et pour donner l'OP aux admins.

import (
	"context"
	"fmt"
	"os/exec"
	"regexp"
	"strings"
	"time"
	"unicode"

	"github.com/gorilla/websocket"
)

const (
	consoleMaxCommand = 256
	consoleMaxOutput  = 4000
	consoleTimeout    = 15 * time.Second
)

// Codes couleur Minecraft (§a, §l...) retirés de la réponse
var mcColorCodes = regexp.MustCompile(`§.`)

// validConsoleCommand : une seule ligne, pas de caractère de contrôle
func validConsoleCommand(cmd string) error {
	cmd = strings.TrimSpace(cmd)
	if cmd == "" {
		return fmt.Errorf("commande vide")
	}
	if len(cmd) > consoleMaxCommand {
		return fmt.Errorf("commande trop longue (%d caractères maximum)", consoleMaxCommand)
	}
	for _, r := range cmd {
		if unicode.IsControl(r) {
			return fmt.Errorf("caractère interdit dans la commande")
		}
	}
	return nil
}

func cleanConsoleOutput(out string) string {
	out = mcColorCodes.ReplaceAllString(out, "")
	out = strings.TrimSpace(out)
	if len(out) > consoleMaxOutput {
		out = out[:consoleMaxOutput] + "…"
	}
	return out
}

// RunConsoleCommand exécute cmd dans le serveur serverID et renvoie la réponse
func RunConsoleCommand(serverID int64, cmd string) (string, error) {
	if err := validConsoleCommand(cmd); err != nil {
		return "", err
	}
	cmd = strings.TrimPrefix(strings.TrimSpace(cmd), "/")

	ctx, cancel := context.WithTimeout(context.Background(), consoleTimeout)
	defer cancel()
	// Argument unique : rcon-cli l'envoie tel quel, sans passer par un shell
	out, err := exec.CommandContext(ctx, "docker", "exec", containerName(serverID), "rcon-cli", cmd).CombinedOutput()
	if ctx.Err() == context.DeadlineExceeded {
		return "", fmt.Errorf("pas de réponse du serveur (délai dépassé)")
	}
	if err != nil {
		msg := cleanConsoleOutput(string(out))
		if strings.Contains(msg, "is not running") || strings.Contains(msg, "No such container") {
			return "", fmt.Errorf("le serveur n'est pas en marche")
		}
		if msg == "" {
			msg = err.Error()
		}
		return "", fmt.Errorf("console indisponible : %s", msg)
	}
	return cleanConsoleOutput(string(out)), nil
}

func handleConsoleCommand(conn *websocket.Conn, commandId string, msg map[string]interface{}) {
	serverID, ok := getServerID(msg)
	if !ok {
		sendCommandError(conn, commandId, "missing_server_id", "server_id manquant")
		return
	}
	data, _ := msg["data"].(map[string]interface{})
	cmd, _ := data["command"].(string)

	output, err := RunConsoleCommand(serverID, cmd)
	if err != nil {
		sendCommandError(conn, commandId, "console_failed", err.Error())
		return
	}
	sendCommandResult(conn, commandId, map[string]interface{}{
		"server_id": serverID,
		"output":    output,
	})
}
