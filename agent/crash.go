package main

// Lot 76e : pourquoi un serveur s'est arrêté juste après son démarrage. Docker le relance tout
// seul (--restart unless-stopped) : l'état du conteneur est alors celui du nouveau départ, mais
// son journal garde la fin du départ précédent. Le joueur voit une cause courte (sans IP) ; le
// journal de l'agent garde les dernières lignes avant l'arrêt.

import (
	"log"
	"os/exec"
	"regexp"
	"strings"
)

var ipv4 = regexp.MustCompile(`\b\d{1,3}(\.\d{1,3}){3}(:\d+)?\b`)

func maskIP(s string) string {
	return ipv4.ReplaceAllString(s, "x.x.x.x")
}

// crashCause : la cause probable à partir de l'état du conteneur et de son journal
func crashCause(container string) string {
	state, _ := exec.Command("docker", "inspect", "-f", "{{.State.OOMKilled}} {{.State.ExitCode}}", container).Output()
	out, _ := exec.Command("docker", "logs", "--tail", "400", container).CombinedOutput()
	cause, before := crashCauseFrom(strings.TrimSpace(string(state)), string(out))
	if len(before) > 0 {
		log.Printf("Serveur %s : dernières lignes avant l'arrêt :\n%s", container, maskIP(strings.Join(before, "\n")))
	}
	return cause
}

// crashCauseFrom (testable) : state = « OOMKilled ExitCode », logs = docker logs
func crashCauseFrom(state, logs string) (string, []string) {
	lines := strings.Split(strings.ReplaceAll(logs, "\r", ""), "\n")
	// Départs successifs : chacun commence par « [init] Running as uid=… » (image itzg). Le départ
	// qui a échoué est l'avant-dernier, lignes [init] comprises (ex. téléchargement raté).
	var starts []int
	for i, l := range lines {
		if strings.HasPrefix(l, "[init] Running as uid=") {
			starts = append(starts, i)
		}
	}
	from, end := 0, len(lines)
	if len(starts) >= 2 {
		from, end = starts[len(starts)-2], starts[len(starts)-1]
	} else if len(starts) == 1 {
		from = starts[0]
	}
	start := end - 40
	if start < from {
		start = from
	}
	before := lines[start:end]
	for _, l := range before {
		if strings.HasPrefix(l, "[init]") && (strings.Contains(l, "ERROR") || strings.Contains(strings.ToLower(l), "failed")) {
			t := strings.TrimSpace(strings.TrimPrefix(l, "[init]"))
			if len(t) > 160 {
				t = t[:160] + "…"
			}
			return "erreur de préparation : " + maskIP(t), before
		}
	}
	for _, l := range before {
		if strings.Contains(l, "OutOfMemoryError") {
			return "mémoire Java pleine (OutOfMemoryError) : il faut plus de RAM pour ce serveur", before
		}
	}
	f := strings.Fields(state)
	if len(f) == 2 && f[0] == "true" {
		return "mémoire du conteneur pleine (arrêté par le système) : il faut plus de RAM pour ce serveur", before
	}
	for _, l := range before {
		if strings.Contains(l, "Killed") || strings.Contains(l, "exit code 137") {
			return "arrêté par le système (mémoire pleine ?)", before
		}
	}
	last := ""
	for i := len(before) - 1; i >= 0; i-- {
		if t := strings.TrimSpace(before[i]); t != "" && !strings.HasPrefix(t, ">") {
			last = t
			break
		}
	}
	if len(last) > 160 {
		last = last[:160] + "…"
	}
	if last == "" {
		return "", before
	}
	return "dernière ligne : " + maskIP(last), before
}
