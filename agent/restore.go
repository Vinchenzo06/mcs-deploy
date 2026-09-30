package main

// Restauration : remplace les données d'un serveur (arrêté) par un instantané
// restic, pris sur cette machine (sauvegardes locales) ou au central (dépôt de
// cette machine sur le serveur de sauvegarde, lecture seule suffit). Aussi utilisé
// à la création, pour recréer un serveur supprimé à partir de sa sauvegarde.
//
// Étapes : instantané restauré dans <data>/.restore/<id>, puis échange avec le
// dossier du serveur. Les dossiers exclus des sauvegardes (bibliothèques, jar du
// serveur...) sont repris de l'ancien dossier quand ils existent, sinon l'image
// itzg les télécharge au démarrage.

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const restoreTimeout = 2 * time.Hour

// Serveurs en cours de restauration : un démarrage est refusé pendant ce temps
var restoring sync.Map

type restoreRequest struct {
	ServerID     int64  `json:"server_id"`
	Local        bool   `json:"local"`
	Repository   string `json:"repository"`
	HTTPUser     string `json:"http_user"`
	HTTPPassword string `json:"http_password"`
	RepoPassword string `json:"repo_password"`
	SnapshotID   string `json:"snapshot_id"`
}

type restoreResult struct {
	SnapshotID string `json:"snapshot_id"`
	RestoredMB int64  `json:"restored_mb"`
	Seconds    int64  `json:"seconds"`
}

func isRestoring(serverID int64) bool {
	_, ok := restoring.Load(serverID)
	return ok
}

// Repris de l'ancien dossier s'ils manquent dans l'instantané (voir backupExcludes)
var restoreKeep = []string{"libraries", "versions", "cache", ".cache"}

// RestoreServer restaure l'instantané dans le dossier du serveur, qui doit être arrêté
func RestoreServer(dataPath string, req restoreRequest) (restoreResult, error) {
	if !snapshotIDPattern.MatchString(req.SnapshotID) {
		return restoreResult{}, fmt.Errorf("instantané invalide")
	}
	lock, _ := backupLocks.LoadOrStore(req.ServerID, &sync.Mutex{})
	mu := lock.(*sync.Mutex)
	if !mu.TryLock() {
		return restoreResult{}, fmt.Errorf("une sauvegarde ou une restauration de ce serveur est déjà en cours")
	}
	defer mu.Unlock()
	restoring.Store(req.ServerID, true)
	defer restoring.Delete(req.ServerID)

	if containerRunning(req.ServerID) {
		return restoreResult{}, fmt.Errorf("le serveur tourne encore : il doit être arrêté avant la restauration")
	}
	return restoreInto(dataPath, req)
}

func restoreInto(dataPath string, req restoreRequest) (restoreResult, error) {
	if _, err := exec.LookPath("restic"); err != nil {
		return restoreResult{}, fmt.Errorf("restic absent de la machine (relance node-setup.sh --update-agent)")
	}
	var env []string
	var err error
	if req.Local {
		repo := localRepoPath(dataPath, req.ServerID)
		if _, err := os.Stat(filepath.Join(repo, "config")); err != nil {
			return restoreResult{}, fmt.Errorf("pas de sauvegardes de ce serveur sur cette machine")
		}
		env, err = localEnv(repo, req.RepoPassword)
	} else {
		env, err = resticEnv(backupRequest{Repository: req.Repository, HTTPUser: req.HTTPUser,
			HTTPPassword: req.HTTPPassword, RepoPassword: req.RepoPassword})
		if err == nil {
			err = ensureBackupRoute(req.Repository)
		}
	}
	if err != nil {
		return restoreResult{}, err
	}

	ctx, cancel := context.WithTimeout(context.Background(), restoreTimeout)
	defer cancel()
	start := time.Now()

	// Instantané : chemin sauvegardé et taille à restaurer
	out, err := runRestic(ctx, env, "snapshots", "--json", req.SnapshotID)
	if err != nil {
		return restoreResult{}, err
	}
	var snaps []struct {
		ID    string   `json:"id"`
		Paths []string `json:"paths"`
	}
	if json.Unmarshal(out, &snaps) != nil || len(snaps) != 1 || len(snaps[0].Paths) != 1 {
		return restoreResult{}, fmt.Errorf("instantané %s introuvable dans le dépôt", req.SnapshotID)
	}
	snapPath := snaps[0].Paths[0]
	var sizeMB int64
	if out, err := runRestic(ctx, env, "stats", "--json", "--mode", "restore-size", snaps[0].ID); err == nil {
		var st struct {
			TotalSize int64 `json:"total_size"`
		}
		if json.Unmarshal(out, &st) == nil {
			sizeMB = st.TotalSize >> 20
		}
	}
	if _, free := diskInfo(dataPath); free > 0 && sizeMB+1024 > free {
		return restoreResult{}, fmt.Errorf("pas assez de place sur la machine pour restaurer (%d Mo nécessaires, %d Mo libres)",
			sizeMB+1024, free)
	}

	tmp := filepath.Join(dataPath, ".restore", fmt.Sprintf("%d", req.ServerID))
	_ = os.RemoveAll(tmp)
	if err := os.MkdirAll(tmp, 0o700); err != nil {
		return restoreResult{}, err
	}
	defer os.RemoveAll(tmp)
	if _, err := runRestic(ctx, env, "restore", snaps[0].ID, "--target", tmp); err != nil {
		return restoreResult{}, err
	}
	src := filepath.Join(tmp, snapPath)
	if st, err := os.Stat(src); err != nil || !st.IsDir() {
		return restoreResult{}, fmt.Errorf("contenu restauré introuvable (%s)", snapPath)
	}

	// Échange avec le dossier du serveur
	dir := serverDataPath(dataPath, req.ServerID)
	old := dir + ".before-restore"
	_ = os.RemoveAll(old)
	hadOld := false
	if _, err := os.Stat(dir); err == nil {
		if err := os.Rename(dir, old); err != nil {
			return restoreResult{}, fmt.Errorf("mise de côté des données actuelles : %w", err)
		}
		hadOld = true
	}
	if err := os.Rename(src, dir); err != nil {
		if hadOld {
			_ = os.Rename(old, dir)
		}
		return restoreResult{}, fmt.Errorf("mise en place des données restaurées : %w", err)
	}
	if err := os.Chown(dir, containerUID, containerGID); err != nil {
		log.Printf("Restauration %d : droits du dossier : %v", req.ServerID, err)
	}
	if hadOld {
		keepRegenerated(old, dir)
		if err := os.RemoveAll(old); err != nil {
			log.Printf("Restauration %d : suppression des anciennes données : %v", req.ServerID, err)
		}
	}
	res := restoreResult{SnapshotID: snaps[0].ID, RestoredMB: sizeMB, Seconds: int64(time.Since(start).Seconds())}
	log.Printf("Serveur %d restauré depuis l'instantané %s (%d Mo, %d s)", req.ServerID, snaps[0].ID[:8], sizeMB, res.Seconds)
	return res, nil
}

// keepRegenerated reprend de l'ancien dossier ce que les sauvegardes excluent
// (bibliothèques, jar du serveur) : démarrage plus rapide, sans nouveau téléchargement
func keepRegenerated(old, dir string) {
	names := append([]string{}, restoreKeep...)
	if entries, err := os.ReadDir(old); err == nil {
		for _, e := range entries {
			if !e.IsDir() && strings.HasSuffix(e.Name(), ".jar") {
				names = append(names, e.Name())
			}
		}
	}
	for _, n := range names {
		from, to := filepath.Join(old, n), filepath.Join(dir, n)
		if _, err := os.Lstat(from); err != nil {
			continue
		}
		if _, err := os.Lstat(to); err == nil {
			continue
		}
		if err := os.Rename(from, to); err != nil {
			log.Printf("Restauration : %s non repris : %v", n, err)
		}
	}
}

func handleRestoreServer(conn *websocket.Conn, config *Config, commandId string, msg map[string]interface{}) {
	data, ok := msg["data"].(map[string]interface{})
	if !ok {
		sendCommandError(conn, commandId, "missing_data", "data manquant")
		return
	}
	raw, _ := json.Marshal(data)
	var req restoreRequest
	if err := json.Unmarshal(raw, &req); err != nil || req.ServerID <= 0 {
		sendCommandError(conn, commandId, "invalid_data", "requête de restauration invalide")
		return
	}
	res, err := RestoreServer(config.Docker.DataPath, req)
	if err != nil {
		sendCommandError(conn, commandId, "restore_failed", err.Error())
		return
	}
	sendCommandResult(conn, commandId, res)
}
