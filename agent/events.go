package main

// Événements Docker en direct : démarrage, arrêt, plantage et santé des
// serveurs sont transmis à l'API dès qu'ils arrivent (et non plus seulement
// par l'inventaire toutes les 5 minutes). "healthy" vient du healthcheck de
// l'image itzg : Minecraft répond réellement, le serveur est joignable.

import (
	"bufio"
	"encoding/json"
	"log"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

type serverEvent struct {
	ServerID int64  `json:"server_id"`
	Event    string `json:"event"` // start, die, healthy, unhealthy
	ExitCode *int   `json:"exit_code,omitempty"`
}

type dockerEvent struct {
	Action string `json:"Action"`
	Actor  struct {
		Attributes map[string]string `json:"Attributes"`
	} `json:"Actor"`
}

// parseDockerEvent : nil si l'événement ne concerne pas un serveur MCS actif
func parseDockerEvent(line string) *serverEvent {
	var ev dockerEvent
	if err := json.Unmarshal([]byte(line), &ev); err != nil {
		return nil
	}
	attrs := ev.Actor.Attributes
	if attrs["mcs.managed"] != "true" || strings.HasPrefix(attrs["name"], orphanPrefix) {
		return nil
	}
	id, err := strconv.ParseInt(attrs["mcs.server_id"], 10, 64)
	if err != nil {
		return nil
	}

	out := &serverEvent{ServerID: id}
	switch {
	case ev.Action == "start":
		out.Event = "start"
	case ev.Action == "die":
		out.Event = "die"
		if code, err := strconv.Atoi(attrs["exitCode"]); err == nil {
			out.ExitCode = &code
		}
	case strings.HasPrefix(ev.Action, "health_status"):
		// "health_status: healthy" (le format exact varie selon les versions de Docker)
		switch {
		case strings.Contains(ev.Action, "unhealthy"):
			out.Event = "unhealthy"
		case strings.Contains(ev.Action, "healthy"):
			out.Event = "healthy"
		default:
			return nil
		}
	default:
		return nil
	}
	return out
}

// watchDockerEvents suit "docker events" tant que l'agent tourne et relance le
// suivi s'il s'interrompt. Les événements sont déposés dans out ; s'il n'y a
// pas de connexion à l'API à ce moment, ils sont perdus (l'inventaire complet
// envoyé à la reconnexion rattrape l'état réel).
func watchDockerEvents(out chan<- serverEvent, stop <-chan struct{}) {
	for {
		cmd := exec.Command("docker", "events",
			"--filter", "type=container",
			"--filter", "label=mcs.managed=true",
			"--format", "{{json .}}")
		stdout, err := cmd.StdoutPipe()
		if err == nil {
			err = cmd.Start()
		}
		if err != nil {
			log.Printf("Suivi des événements Docker impossible : %v", err)
		} else {
			done := make(chan struct{})
			go func() {
				select {
				case <-stop:
					_ = cmd.Process.Kill()
				case <-done:
				}
			}()
			scanner := bufio.NewScanner(stdout)
			scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
			for scanner.Scan() {
				if ev := parseDockerEvent(scanner.Text()); ev != nil {
					select {
					case out <- *ev:
					default: // file pleine : l'inventaire rattrapera
					}
				}
			}
			_ = cmd.Wait()
			close(done)
		}

		select {
		case <-stop:
			return
		case <-time.After(5 * time.Second):
			log.Println("Suivi des événements Docker relancé")
		}
	}
}
