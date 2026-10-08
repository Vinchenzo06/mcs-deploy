package main

// Garde-fous des serveurs (lot 35c).
//
// Un serveur cassé (mauvaise version de Java, plugin défaillant...) peut écrire
// des erreurs en boucle : Paper 1.8.8 sous Java 25 a rempli 5 Go de journal en
// 3 minutes sur le disque d'un volontaire. Toutes les 20 s, l'agent vérifie :
//   - le journal du serveur (logs/latest.log) : s'il grossit trop vite ou
//     devient énorme, le serveur est arrêté et le journal ramené à sa fin ;
//   - les redémarrages en boucle (3 plantages en 5 min) : serveur arrêté ;
//   - les vieux journaux compressés au-delà de 300 Mo : les plus anciens partent.
// L'API est prévenue (événement "flood" ou "crashloop", avec le diagnostic
// Java s'il y en a un) et avertit le propriétaire.

import (
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	guardInterval     = 20 * time.Second
	floodGrowthBytes  = 30 << 20 // +30 Mo en 20 s (1,5 Mo/s) : erreurs en boucle
	floodMaxBytes     = 2 << 30  // journal de plus de 2 Go
	floodKeepBytes    = 1 << 20  // on garde le dernier Mo pour comprendre
	oldLogsMaxBytes   = 300 << 20
	crashLoopRestarts = 3
	crashLoopWindow   = 5 * time.Minute
)

type guardState struct {
	logSize  int64
	restarts int
	crashes  []time.Time
}

// watchServers : boucle de surveillance (lancée au démarrage de l'agent)
func watchServers(dataPath string, events chan<- serverEvent, stop <-chan struct{}) {
	states := map[int64]*guardState{}
	ticker := time.NewTicker(guardInterval)
	defer ticker.Stop()
	tick := 0
	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
		}
		tick++
		out, err := exec.Command("docker", "ps", "-a", "--filter", "label=mcs.managed=true",
			"--format", `{{.Label "mcs.server_id"}} {{.State}}`).Output()
		if err != nil {
			continue
		}
		seen := map[int64]bool{}
		for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
			f := strings.Fields(line)
			if len(f) < 2 {
				continue
			}
			id, err := strconv.ParseInt(f[0], 10, 64)
			if err != nil {
				continue
			}
			seen[id] = true
			st := states[id]
			if st == nil {
				st = &guardState{logSize: -1, restarts: -1}
				states[id] = st
			}
			guardServer(dataPath, id, f[1], st, events)
			if tick%30 == 0 { // toutes les 10 min
				trimOldLogs(filepath.Join(serverDataPath(dataPath, id), "logs"))
			}
		}
		for id := range states {
			if !seen[id] {
				delete(states, id)
			}
		}
	}
}

func guardServer(dataPath string, id int64, state string, st *guardState, events chan<- serverEvent) {
	name := containerName(id)
	if isRestoring(id) || isBackingUp(id) {
		return
	}

	// Redémarrages en boucle
	if out, err := exec.Command("docker", "inspect", "-f", "{{.RestartCount}}", name).Output(); err == nil {
		rc, _ := strconv.Atoi(strings.TrimSpace(string(out)))
		if st.restarts >= 0 && rc > st.restarts {
			for i := 0; i < rc-st.restarts; i++ {
				st.crashes = append(st.crashes, time.Now())
			}
		}
		st.restarts = rc
		recent := st.crashes[:0]
		for _, t := range st.crashes {
			if time.Since(t) < crashLoopWindow {
				recent = append(recent, t)
			}
		}
		st.crashes = recent
		if len(st.crashes) >= crashLoopRestarts && state != "exited" {
			st.crashes = nil
			stopGuarded(id, "crashloop", fmt.Sprintf("%d plantages en %d min", crashLoopRestarts,
				int(crashLoopWindow.Minutes())), events)
			return
		}
	}

	// Journal qui explose
	logFile := filepath.Join(serverDataPath(dataPath, id), "logs", "latest.log")
	info, err := os.Stat(logFile)
	if err != nil {
		st.logSize = -1
		return
	}
	size := info.Size()
	prev := st.logSize
	st.logSize = size
	growth := size - prev
	if (prev >= 0 && growth > floodGrowthBytes) || size > floodMaxBytes {
		detail := fmt.Sprintf("journal à %d Mo", size>>20)
		if prev >= 0 && growth > 0 {
			detail = fmt.Sprintf("journal +%d Mo en %d s (%d Mo au total)", growth>>20, int(guardInterval.Seconds()), size>>20)
		}
		stopGuarded(id, "flood", detail, events)
		if err := keepTail(logFile, floodKeepBytes); err != nil {
			log.Printf("Serveur %d : réduction du journal impossible : %v", id, err)
		} else {
			log.Printf("Serveur %d : journal ramené à son dernier Mo", id)
		}
		st.logSize = -1
	}
}

// stopGuarded arrête le serveur, cherche une cause Java et prévient l'API
func stopGuarded(id int64, kind, detail string, events chan<- serverEvent) {
	name := containerName(id)
	je := diagnoseJava(name)
	log.Printf("Serveur %d arrêté par le garde-fou (%s : %s)", id, kind, detail)
	_ = exec.Command("docker", "stop", "-t", "15", name).Run()
	ev := serverEvent{ServerID: id, Event: kind, Detail: detail}
	if je != nil {
		ev.JavaNeeded = je.Needed
		ev.JavaDirection = je.Direction
		if ev.JavaDirection == "" && ev.JavaNeeded == 0 {
			ev.JavaDirection = "unknown"
		}
		ev.Detail += " ; " + je.Error()
	}
	select {
	case events <- ev:
	default:
	}
}

// keepTail ne garde que la fin d'un fichier (serveur arrêté)
func keepTail(path string, keep int64) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	info, err := f.Stat()
	if err != nil {
		f.Close()
		return err
	}
	start := info.Size() - keep
	if start < 0 {
		start = 0
	}
	if _, err := f.Seek(start, io.SeekStart); err != nil {
		f.Close()
		return err
	}
	tmp := path + ".mcs-tail"
	out, err := os.Create(tmp)
	if err != nil {
		f.Close()
		return err
	}
	_, _ = out.WriteString("[MCS] Journal raccourci : le serveur écrivait des erreurs en boucle, seule la fin est gardée.\n")
	_, cerr := io.Copy(out, f)
	f.Close()
	out.Close()
	if cerr != nil {
		os.Remove(tmp)
		return cerr
	}
	_ = os.Chown(tmp, containerUID, containerGID)
	return os.Rename(tmp, path)
}

// trimOldLogs : journaux compressés au-delà de 300 Mo, les plus anciens partent
func trimOldLogs(dir string) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	type file struct {
		path string
		size int64
		mod  time.Time
	}
	var files []file
	var total int64
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".log.gz") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		files = append(files, file{filepath.Join(dir, e.Name()), info.Size(), info.ModTime()})
		total += info.Size()
	}
	if total <= oldLogsMaxBytes {
		return
	}
	sort.Slice(files, func(i, j int) bool { return files[i].mod.Before(files[j].mod) })
	for _, f := range files {
		if total <= oldLogsMaxBytes {
			break
		}
		if os.Remove(f.path) == nil {
			total -= f.size
		}
	}
}

// verifyResponding : après « Done », le serveur doit vraiment répondre (ping
// Minecraft de l'image itzg). Paper 1.8.8 sous Java 25 affiche « Done » puis
// refuse toutes les connexions en remplissant son journal.
func verifyResponding(name string) error {
	var last string
	for i := 0; i < 8; i++ {
		time.Sleep(4 * time.Second)
		if containerCrashed(name) {
			return javaFailure(name, fmt.Errorf("le serveur s'est arrêté juste après son démarrage"))
		}
		out, err := exec.Command("docker", "exec", name, "mc-health").CombinedOutput()
		if err == nil {
			return nil
		}
		last = strings.TrimSpace(string(out))
		// Après quelques échecs seulement (un serveur qui vient d'afficher « Done »
		// peut mettre quelques secondes à répondre)
		if i >= 2 {
			if je := diagnoseJava(name); je != nil {
				return javaFailure(name, je)
			}
		}
	}
	// Pas de réponse sans cause connue : on laisse tourner (gros modpack lent ?)
	log.Printf("Serveur %s : prêt mais ne répond pas encore au ping (%s)", name, last)
	return nil
}
