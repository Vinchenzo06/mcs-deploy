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
// Lot 76e : si un départ échoue pendant la préparation (ex. API Modrinth trop lente), Docker
// relance le conteneur : on recommence à lire, jusqu'à maxPrepRestarts relances.
func waitForMinecraftReady(containerName string, timeoutSeconds int) error {
	log.Printf("Attente que le serveur %s soit prêt (timeout %ds)...", containerName, timeoutSeconds)

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSeconds)*time.Second)
	defer cancel()

	_, base, _ := dockerState(containerName)
	since := ""
	for {
		ready, err := followUntilReady(ctx, containerName, since)
		if ready || err != nil {
			return err
		}
		if ctx.Err() != nil {
			return fmt.Errorf("timeout après %d secondes", timeoutSeconds)
		}
		// Le journal s'est arrêté : le conteneur est sorti. Relancé par Docker ?
		since = time.Now().Add(-2 * time.Second).UTC().Format(time.RFC3339)
		restarted := false
		for i := 0; i < 20 && ctx.Err() == nil; i++ {
			st, n, ok := dockerState(containerName)
			if ok && n > base && (st == "running" || st == "restarting") {
				if n-base > maxPrepRestarts {
					return fmt.Errorf("le serveur redémarre en boucle (%s)", crashCause(containerName))
				}
				restarted = true
				break
			}
			time.Sleep(500 * time.Millisecond)
		}
		if !restarted {
			if cause := crashCause(containerName); cause != "" {
				return fmt.Errorf("le serveur s'est arrêté avant d'être prêt (%s)", cause)
			}
			return fmt.Errorf("docker logs s'est arrêté avant que le serveur soit prêt")
		}
		_, base, _ = dockerState(containerName)
		log.Printf("Serveur %s : départ raté pendant la préparation (%s), relancé par Docker, on attend le suivant",
			containerName, crashCause(containerName))
	}
}

// followUntilReady : lit le journal jusqu'à « Done » (true), une erreur, ou la fin du journal (false, nil)
func followUntilReady(ctx context.Context, containerName, since string) (bool, error) {
	args := []string{"logs", "-f", "--tail", "0", containerName}
	if since != "" {
		args = []string{"logs", "-f", "--since", since, containerName}
	}
	cmd := exec.CommandContext(ctx, "docker", args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return false, fmt.Errorf("erreur stdout : %w", err)
	}
	cmd.Stderr = cmd.Stdout // Mélange stderr et stdout, Paper logue parfois sur stderr

	if err := cmd.Start(); err != nil {
		return false, fmt.Errorf("erreur démarrage docker logs : %w", err)
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
		if ctx.Err() != nil {
			return false, nil
		}
		line := scanner.Text()

		// Cherche le marqueur officiel de Paper / Spigot / Vanilla : "Done (X.XXXs)! For help, type "help""
		if strings.Contains(line, "Done (") && strings.Contains(line, "For help") {
			log.Printf("Serveur %s prêt !", containerName)
			return true, nil
		}

		// Mauvaise version de Java : inutile d'attendre la fin du délai
		if je := diagnoseJavaLine(line); je != nil {
			return false, je
		}

		// Détecter aussi un démarrage qui a échoué
		if strings.Contains(line, "FAILED TO BIND TO PORT") {
			return false, fmt.Errorf("le serveur n'a pas pu démarrer : port déjà utilisé")
		}
	}
	return false, nil
}
