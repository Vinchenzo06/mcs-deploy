package main

// Sauvegardes : restic envoie les données d'un serveur vers le serveur de
// sauvegarde, joint par le VPS (rest:http://10.99.0.1:8100/node<id>/, à travers
// le tunnel WireGuard). Le dépôt est chiffré et en ajout seul côté serveur de
// sauvegarde : cette machine ne peut ni lire les autres dépôts ni effacer ses
// anciennes sauvegardes. Les identifiants arrivent avec chaque commande (rien
// n'est gardé sur le disque).

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	backupTimeout  = 2 * time.Hour
	resticCacheDir = "/var/cache/mcs-restic"
)

// Une sauvegarde à la fois par serveur
var backupLocks sync.Map

type backupRequest struct {
	ServerID     int64  `json:"server_id"`
	Repository   string `json:"repository"` // rest:http://10.99.0.1:8100/node2/
	HTTPUser     string `json:"http_user"`
	HTTPPassword string `json:"http_password"`
	RepoPassword string `json:"repo_password"`
	Tag          string `json:"tag"`
	Host         string `json:"host"`
}

type backupResult struct {
	SnapshotID string `json:"snapshot_id"`
	DataAdded  int64  `json:"data_added"`
	TotalBytes int64  `json:"total_bytes"`
	Files      int64  `json:"files"`
	Seconds    int64  `json:"seconds"`
}

// Dossiers régénérés par l'image itzg : inutile de les sauvegarder
var backupExcludes = []string{"logs", "cache", ".cache", "libraries", "versions", "crash-reports", "*.jar"}

// resticEnv : dépôt avec identifiants HTTP intégrés (en variable d'environnement,
// jamais dans la ligne de commande visible par ps)
func resticEnv(req backupRequest) ([]string, error) {
	repo := strings.TrimPrefix(req.Repository, "rest:")
	u, err := url.Parse(repo)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return nil, fmt.Errorf("dépôt invalide : %q", req.Repository)
	}
	if req.HTTPUser != "" {
		u.User = url.UserPassword(req.HTTPUser, req.HTTPPassword)
	}
	if req.RepoPassword == "" {
		return nil, fmt.Errorf("mot de passe du dépôt manquant")
	}
	_ = os.MkdirAll(resticCacheDir, 0o700)
	return append(os.Environ(),
		"RESTIC_REPOSITORY=rest:"+u.String(),
		"RESTIC_PASSWORD="+req.RepoPassword,
		"RESTIC_CACHE_DIR="+resticCacheDir,
	), nil
}

func runRestic(ctx context.Context, env []string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, "restic", args...)
	cmd.Env = env
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		msg := strings.TrimSpace(stderr.String())
		if len(msg) > 400 {
			msg = msg[len(msg)-400:]
		}
		return out, fmt.Errorf("restic %s : %v : %s", args[0], err, msg)
	}
	return out, nil
}

// ensureRepo crée le dépôt s'il n'existe pas encore (autorisé en ajout seul)
func ensureRepo(ctx context.Context, env []string) error {
	if _, err := runRestic(ctx, env, "cat", "config"); err == nil {
		return nil
	}
	if _, err := runRestic(ctx, env, "init"); err != nil {
		// Créé entre-temps par une autre sauvegarde ?
		if _, err2 := runRestic(ctx, env, "cat", "config"); err2 == nil {
			return nil
		}
		return err
	}
	log.Printf("Dépôt de sauvegarde créé")
	return nil
}

// parseBackupSummary lit la ligne "summary" de restic backup --json
func parseBackupSummary(out []byte) (backupResult, error) {
	var res backupResult
	sc := bufio.NewScanner(bytes.NewReader(out))
	sc.Buffer(make([]byte, 1024*1024), 16*1024*1024)
	found := false
	for sc.Scan() {
		var line struct {
			MessageType         string  `json:"message_type"`
			SnapshotID          string  `json:"snapshot_id"`
			DataAdded           int64   `json:"data_added"`
			TotalBytesProcessed int64   `json:"total_bytes_processed"`
			TotalFilesProcessed int64   `json:"total_files_processed"`
			TotalDuration       float64 `json:"total_duration"`
		}
		if json.Unmarshal(sc.Bytes(), &line) != nil || line.MessageType != "summary" {
			continue
		}
		res = backupResult{
			SnapshotID: line.SnapshotID,
			DataAdded:  line.DataAdded,
			TotalBytes: line.TotalBytesProcessed,
			Files:      line.TotalFilesProcessed,
			Seconds:    int64(line.TotalDuration),
		}
		found = true
	}
	if !found {
		return res, fmt.Errorf("résumé restic introuvable")
	}
	return res, nil
}

// BackupServer sauvegarde un serveur ; s'il tourne, l'écriture du monde est
// suspendue le temps de la copie (save-off / save-all flush / save-on)
func BackupServer(dataPath string, req backupRequest) (backupResult, error) {
	lock, _ := backupLocks.LoadOrStore(req.ServerID, &sync.Mutex{})
	mu := lock.(*sync.Mutex)
	if !mu.TryLock() {
		return backupResult{}, fmt.Errorf("une sauvegarde de ce serveur est déjà en cours")
	}
	defer mu.Unlock()

	if _, err := exec.LookPath("restic"); err != nil {
		return backupResult{}, fmt.Errorf("restic absent de la machine (relance node-setup.sh --update-agent)")
	}
	dir := serverDataPath(dataPath, req.ServerID)
	if st, err := os.Stat(dir); err != nil || !st.IsDir() {
		return backupResult{}, fmt.Errorf("dossier du serveur introuvable : %s", dir)
	}
	env, err := resticEnv(req)
	if err != nil {
		return backupResult{}, err
	}

	if err := ensureBackupRoute(req.Repository); err != nil {
		return backupResult{}, err
	}

	ctx, cancel := context.WithTimeout(context.Background(), backupTimeout)
	defer cancel()
	if err := ensureRepo(ctx, env); err != nil {
		return backupResult{}, err
	}

	running := containerRunning(req.ServerID)
	if running {
		if _, err := RunConsoleCommand(req.ServerID, "save-off"); err != nil {
			log.Printf("Sauvegarde %d : save-off impossible (%v), copie à chaud", req.ServerID, err)
		} else {
			defer func() {
				if _, err := RunConsoleCommand(req.ServerID, "save-on"); err != nil {
					log.Printf("Sauvegarde %d : save-on impossible : %v", req.ServerID, err)
				}
			}()
			if _, err := RunConsoleCommand(req.ServerID, "save-all flush"); err != nil {
				log.Printf("Sauvegarde %d : save-all impossible : %v", req.ServerID, err)
			}
			time.Sleep(2 * time.Second)
		}
	}

	args := []string{"backup", "--json", "--tag", req.Tag, "--host", req.Host}
	for _, ex := range backupExcludes {
		args = append(args, "--exclude", filepath.Join(dir, ex))
	}
	args = append(args, dir)
	start := time.Now()
	out, err := runRestic(ctx, env, args...)
	if err != nil {
		return backupResult{}, err
	}
	res, err := parseBackupSummary(out)
	if err != nil {
		return backupResult{}, err
	}
	if res.Seconds == 0 {
		res.Seconds = int64(time.Since(start).Seconds())
	}
	log.Printf("Sauvegarde du serveur %d : instantané %s, %d octets nouveaux", req.ServerID, res.SnapshotID, res.DataAdded)
	return res, nil
}

// ensureBackupRoute : le VPS (10.99.0.1) est joint par le tunnel wg-mcs. Le
// tunnel est en "Table = off" (seuls les conteneurs y passent) : on ajoute la
// route de cette seule adresse pour l'agent. Les conteneurs, eux, restent
// bloqués vers 10.0.0.0/8 (isolation).
func ensureBackupRoute(repository string) error {
	u, err := url.Parse(strings.TrimPrefix(repository, "rest:"))
	if err != nil {
		return fmt.Errorf("dépôt invalide")
	}
	host := u.Hostname()
	if !strings.HasPrefix(host, "10.99.") {
		return nil
	}
	out, err := exec.Command("ip", "-4", "route", "replace", host+"/32", "dev", egressIface).CombinedOutput()
	if err != nil {
		return fmt.Errorf("route vers le VPS par %s impossible : %s", egressIface, strings.TrimSpace(string(out)))
	}
	return nil
}

// containerRunning : le conteneur du serveur tourne-t-il ?
func containerRunning(serverID int64) bool {
	out, err := exec.Command("docker", "inspect", "-f", "{{.State.Running}}", containerName(serverID)).Output()
	return err == nil && strings.TrimSpace(string(out)) == "true"
}

func handleBackupServer(conn *websocket.Conn, config *Config, commandId string, msg map[string]interface{}) {
	data, ok := msg["data"].(map[string]interface{})
	if !ok {
		sendCommandError(conn, commandId, "missing_data", "data manquant")
		return
	}
	raw, _ := json.Marshal(data)
	var req backupRequest
	if err := json.Unmarshal(raw, &req); err != nil || req.ServerID <= 0 {
		sendCommandError(conn, commandId, "invalid_data", "requête de sauvegarde invalide")
		return
	}
	res, err := BackupServer(config.Docker.DataPath, req)
	if err != nil {
		sendCommandError(conn, commandId, "backup_failed", err.Error())
		return
	}
	sendCommandResult(conn, commandId, res)
}
