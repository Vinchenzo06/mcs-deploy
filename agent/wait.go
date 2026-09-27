package main

import (
	"bufio"
	"context"
	"fmt"
	"log"
	"os/exec"
	"strings"
	"time"
)

// waitForMinecraftReady attend que le serveur Minecraft soit prêt en lisant les logs du conteneur.
// Retourne nil si le serveur est prêt, une erreur sinon (timeout, plantage, etc.)
func waitForMinecraftReady(containerName string, timeoutSeconds int) error {
	log.Printf("Attente que le serveur %s soit prêt (timeout %ds)...", containerName, timeoutSeconds)

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSeconds)*time.Second)
	defer cancel()

	// Lance docker logs -f sur le conteneur pour suivre les logs en temps réel
	cmd := exec.CommandContext(ctx, "docker", "logs", "-f", "--tail", "0", containerName)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return fmt.Errorf("erreur stdout : %w", err)
	}
	cmd.Stderr = cmd.Stdout // Mélange stderr et stdout, Paper logue parfois sur stderr

	if err := cmd.Start(); err != nil {
		return fmt.Errorf("erreur démarrage docker logs : %w", err)
	}

	// Garantir que le process docker logs soit nettoyé
	defer func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
	}()

	scanner := bufio.NewScanner(stdout)
	// Augmenter la taille du buffer pour les longues lignes de log
	buf := make([]byte, 0, 1024*1024)
	scanner.Buffer(buf, 1024*1024)

	for scanner.Scan() {
		select {
		case <-ctx.Done():
			return fmt.Errorf("timeout après %d secondes", timeoutSeconds)
		default:
		}

		line := scanner.Text()

		// Cherche le marqueur officiel de Paper / Spigot / Vanilla : "Done (X.XXXs)! For help, type "help""
		if strings.Contains(line, "Done (") && strings.Contains(line, "For help") {
			log.Printf("Serveur %s prêt !", containerName)
			return nil
		}

		// Détecter aussi un démarrage qui a échoué
		if strings.Contains(line, "FAILED TO BIND TO PORT") {
			return fmt.Errorf("le serveur n'a pas pu démarrer : port déjà utilisé")
		}
	}

	if err := ctx.Err(); err != nil {
		return fmt.Errorf("timeout après %d secondes", timeoutSeconds)
	}

	return fmt.Errorf("docker logs s'est arrêté avant que le serveur soit prêt")
}
