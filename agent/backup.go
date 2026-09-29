package main

// Sauvegardes : restic envoie les données d'un serveur vers le serveur de
// sauvegarde, joint par le VPS (rest:http://10.99.0.1:8100/node<id>/, à travers
// le tunnel WireGuard). Le dépôt est chiffré et en ajout seul côté serveur de
// sauvegarde : cette machine ne peut ni lire les autres dépôts ni effacer ses
// anciennes sauvegardes. Les identifiants arrivent avec chaque commande (rien
// n'est gardé sur le disque).
//
// Sauvegardes locales (si le volontaire l'accepte, backups.local dans config.yaml) :
// un dépôt restic par serveur dans <data>/.backups/<id>, hors de la vue des
// conteneurs. Elles comptent dans le quota disque du serveur : avant la copie,
// l'agent supprime les instantanés expirés (liste de l'API), puis, s'il manque de
// la place, les plus anciens que l'API l'autorise à supprimer ; sinon il saute la
// sauvegarde ("no_space") et l'API l'envoie au central.

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
	"regexp"
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
	// Sauvegarde sur cette machine
	Local      bool     `json:"local"`
	QuotaMB    int64    `json:"quota_mb"`
	EstimateMB int64    `json:"estimate_mb"`
	Forget     []string `json:"forget"`
	Evict      []string `json:"evict"`
}

type backupResult struct {
	SnapshotID string `json:"snapshot_id"`
	DataAdded  int64  `json:"data_added"`
	TotalBytes int64  `json:"total_bytes"`
	Files      int64  `json:"files"`
	Seconds    int64  `json:"seconds"`
	// Sauvegardes locales
	Skipped string    `json:"skipped,omitempty"`
	Evicted []string  `json:"evicted"`
	Present *[]string `json:"present,omitempty"`
	RepoMB  int64     `json:"repo_mb"`
	DataMB  int64     `json:"data_mb,omitempty"`
	QuotaMB int64     `json:"quota_mb,omitempty"`
}

var snapshotIDPattern = regexp.MustCompile(`^[0-9a-f]{8,64}$`)

// localRepoPath : dépôt des sauvegardes locales d'un serveur
func localRepoPath(dataPath string, serverID int64) string {
	return filepath.Join(dataPath, ".backups", fmt.Sprintf("%d", serverID))
}

func localEnv(repo, password string) ([]string, error) {
	if password == "" {
		return nil, fmt.Errorf("mot de passe du dépôt manquant")
	}
	_ = os.MkdirAll(resticCacheDir, 0o700)
	return append(os.Environ(),
		"RESTIC_REPOSITORY="+repo,
		"RESTIC_PASSWORD="+password,
		"RESTIC_CACHE_DIR="+resticCacheDir,
	), nil
}

// snapshotIDs : identifiants complets des instantanés du dépôt
func snapshotIDs(ctx context.Context, env []string) ([]string, error) {
	out, err := runRestic(ctx, env, "snapshots", "--json")
	if err != nil {
		return nil, err
	}
	var list []struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(out, &list); err != nil {
		return nil, fmt.Errorf("liste des instantanés illisible : %v", err)
	}
	ids := make([]string, 0, len(list))
	for _, s := range list {
		ids = append(ids, s.ID)
	}
	return ids, nil
}

// resolveIDs : identifiants demandés (courts ou longs) présents dans le dépôt
func resolveIDs(wanted, present []string) []string {
	var out []string
	for _, w := range wanted {
		if !snapshotIDPattern.MatchString(w) {
			continue
		}
		for _, p := range present {
			if strings.HasPrefix(p, w) {
				out = append(out, p)
				break
			}
		}
	}
	return out
}

func forgetIDs(ctx context.Context, env []string, ids []string) error {
	if len(ids) == 0 {
		return nil
	}
	args := append([]string{"forget", "--prune"}, ids...)
	_, err := runRestic(ctx, env, args...)
	return err
}

// prepareLocal : supprime les instantanés expirés, puis fait de la place dans le
// quota du serveur. Renvoie skip = true s'il n'y a pas assez de place.
func prepareLocal(ctx context.Context, env []string, dir, repo string, req backupRequest, res *backupResult) (bool, error) {
	present, err := snapshotIDs(ctx, env)
	if err != nil {
		return false, err
	}
	if expired := resolveIDs(req.Forget, present); len(expired) > 0 {
		if err := forgetIDs(ctx, env, expired); err != nil {
			return false, err
		}
		log.Printf("Sauvegarde %d : %d instantané(s) local(aux) expiré(s) supprimé(s)", req.ServerID, len(expired))
		if present, err = snapshotIDs(ctx, env); err != nil {
			return false, err
		}
	}
	res.DataMB = dataUsedMB(dir)
	res.RepoMB = dataUsedMB(repo)
	res.QuotaMB = req.QuotaMB
	if req.QuotaMB <= 0 {
		return false, nil
	}
	estimate := func() int64 {
		if len(present) == 0 || req.EstimateMB <= 0 {
			return res.DataMB // premier instantané : au plus la taille des données
		}
		return req.EstimateMB
	}
	evict := resolveIDs(req.Evict, present)
	for res.DataMB+res.RepoMB+estimate() > req.QuotaMB && len(evict) > 0 {
		id := evict[0]
		evict = evict[1:]
		if err := forgetIDs(ctx, env, []string{id}); err != nil {
			return false, err
		}
		res.Evicted = append(res.Evicted, id)
		log.Printf("Sauvegarde %d : instantané local %s supprimé pour faire de la place", req.ServerID, id[:8])
		if present, err = snapshotIDs(ctx, env); err != nil {
			return false, err
		}
		res.RepoMB = dataUsedMB(repo)
	}
	if res.DataMB+res.RepoMB+estimate() > req.QuotaMB {
		res.Skipped = "no_space"
		res.Present = &present
		log.Printf("Sauvegarde %d : pas assez de place dans le quota (%d Mo de données, %d Mo de sauvegardes, quota %d Mo)",
			req.ServerID, res.DataMB, res.RepoMB, req.QuotaMB)
		return true, nil
	}
	return false, nil
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
	var env []string
	var err error
	var res backupResult
	res.Evicted = []string{}
	repo := ""
	ctx, cancel := context.WithTimeout(context.Background(), backupTimeout)
	defer cancel()
	if req.Local {
		repo = localRepoPath(dataPath, req.ServerID)
		if err := os.MkdirAll(filepath.Dir(repo), 0o700); err != nil {
			return backupResult{}, err
		}
		if env, err = localEnv(repo, req.RepoPassword); err != nil {
			return backupResult{}, err
		}
		if err := ensureRepo(ctx, env); err != nil {
			return backupResult{}, err
		}
		skip, err := prepareLocal(ctx, env, dir, repo, req, &res)
		if err != nil {
			return backupResult{}, err
		}
		if skip {
			return res, nil
		}
	} else {
		if env, err = resticEnv(req); err != nil {
			return backupResult{}, err
		}
		if err := ensureBackupRoute(req.Repository); err != nil {
			return backupResult{}, err
		}
		if err := ensureRepo(ctx, env); err != nil {
			return backupResult{}, err
		}
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
	sum, err := parseBackupSummary(out)
	if err != nil {
		return backupResult{}, err
	}
	res.SnapshotID, res.DataAdded, res.TotalBytes, res.Files, res.Seconds =
		sum.SnapshotID, sum.DataAdded, sum.TotalBytes, sum.Files, sum.Seconds
	if res.Seconds == 0 {
		res.Seconds = int64(time.Since(start).Seconds())
	}
	if req.Local {
		if present, err := snapshotIDs(ctx, env); err == nil {
			res.Present = &present
		}
		res.RepoMB = dataUsedMB(repo)
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

// localBackupsStartup : le volontaire ne garde plus de sauvegardes -> on rend la place
func localBackupsStartup(config *Config) {
	dir := filepath.Join(config.Docker.DataPath, ".backups")
	if config.Backups.Local {
		log.Printf("Sauvegardes des serveurs gardées sur cette machine : oui (%s)", dir)
		return
	}
	if _, err := os.Stat(dir); err == nil {
		if err := os.RemoveAll(dir); err != nil {
			log.Printf("Suppression des anciennes sauvegardes locales : %v", err)
		} else {
			log.Printf("Sauvegardes locales désactivées : %s supprimé", dir)
		}
	}
}
