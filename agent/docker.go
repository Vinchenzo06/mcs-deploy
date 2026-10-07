package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// CreateServerRequest contient tous les paramètres pour créer un serveur Minecraft
type CreateServerRequest struct {
	ServerID  int64  `json:"server_id"`
	Type      string `json:"type"`       // PAPER, SPIGOT, FORGE, FABRIC, VANILLA
	Version   string `json:"version"`    // 1.21.4, 1.20.1, etc.
	Port      int    `json:"port"`       // Port externe sur la machine
	RamMb     int    `json:"ram_mb"`     // RAM allouée
	CpuCores  int    `json:"cpu_cores"`  // Cores CPU alloués
	StorageMb int    `json:"storage_mb"` // Stockage max
	OwnerName string `json:"owner_name"` // Pour la séparation logique
	// Version de Java (image itzg javaN) ; 0 = la plus récente (API d'avant le lot 35)
	Java int `json:"java"`
	// Serveur recréé à partir d'une sauvegarde (serveur supprimé, lot 31)
	Restore *restoreRequest `json:"restore,omitempty"`
}

// ContainerName retourne le nom standardisé du conteneur pour un serveur donné
func containerName(serverID int64) string {
	return fmt.Sprintf("mcs-server-%d", serverID)
}

// serverDataPath retourne le chemin du dossier de données pour un serveur
func serverDataPath(dataPath string, serverID int64) string {
	return filepath.Join(dataPath, fmt.Sprintf("%d", serverID))
}

// CreateServer crée un nouveau serveur Minecraft via Docker
func CreateServer(dataPath string, req CreateServerRequest) error {
	log.Printf("Création du serveur id=%d type=%s version=%s port=%d ram=%dMo",
		req.ServerID, req.Type, req.Version, req.Port, req.RamMb)

	// Jamais de serveur sans isolation réseau
	if !isolationReady.Load() {
		if err := ensureIsolation(); err != nil {
			return fmt.Errorf("isolation réseau indisponible, création refusée : %w", err)
		}
	}

	// Sortie via le VPS : sans tunnel actif, le serveur ne pourrait rien télécharger
	if err := egressTunnelUp(); err != nil {
		return fmt.Errorf("création refusée : %w", err)
	}

	// Image Docker présente avant tout (sinon docker run la télécharge sans rien dire)
	if err := ensureImage(imageFor(req.Java)); err != nil {
		return err
	}

	// Vérifier qu'il n'y a pas déjà un conteneur avec ce nom
	if exists, _ := containerExists(containerName(req.ServerID)); exists {
		return fmt.Errorf("un conteneur existe déjà pour ce serveur")
	}

	serverPath := serverDataPath(dataPath, req.ServerID)

	// Créer le dossier de données, propriété de l'utilisateur du conteneur
	if err := os.MkdirAll(serverPath, 0755); err != nil {
		return fmt.Errorf("création du dossier : %w", err)
	}
	if err := os.Chown(serverPath, containerUID, containerGID); err != nil {
		return fmt.Errorf("droits du dossier : %w", err)
	}

	// Recréation depuis une sauvegarde : les données sont en place avant le premier démarrage
	if req.Restore != nil {
		r := *req.Restore
		r.ServerID = req.ServerID
		restoring.Store(req.ServerID, true)
		_, err := restoreInto(dataPath, r)
		restoring.Delete(req.ServerID)
		if err != nil {
			_ = os.RemoveAll(serverPath)
			return fmt.Errorf("restauration de la sauvegarde : %w", err)
		}
	}

	args := runArgs(serverPath, req, "run", "-d")
	out, err := exec.Command("docker", args...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("docker run a échoué : %s : %w", string(out), err)
	}
	log.Printf("Conteneur créé : %s (%s)", strings.TrimSpace(string(out)), imageFor(req.Java))

	return firstStart(serverPath, req)
}

// runArgs : arguments de docker run / docker create pour ce serveur
func runArgs(serverPath string, req CreateServerRequest, verb ...string) []string {
	args := append([]string{}, verb...)
	args = append(args,
		"--name", containerName(req.ServerID),
		"--restart", "unless-stopped",
		"-p", fmt.Sprintf("127.0.0.1:%d:25565", req.Port),
		"-v", fmt.Sprintf("%s:/data", serverPath),
		"-e", "EULA=TRUE",
		"-e", "ONLINE_MODE=FALSE",
		"-e", "TYPE="+mapServerType(req.Type),
		"-e", "VERSION="+req.Version,
		"-e", fmt.Sprintf("MEMORY=%dM", javaHeapMb(req.RamMb)),
		"-e", "ENABLE_QUERY=false",
		// RCON local au conteneur (port non publié) : /mcs console via rcon-cli
		"-e", "ENABLE_RCON=true",
		// Les commandes de MCS (OP, équipes...) ne s'affichent pas aux OP
		"-e", "BROADCAST_RCON_TO_OPS=false",
		"--cpus", fmt.Sprintf("%d", req.CpuCores),
	)
	args = append(args, hardeningArgs(req.RamMb)...)
	args = append(args,
		"--label", "mcs.managed=true",
		"--label", fmt.Sprintf("mcs.server_id=%d", req.ServerID),
		"--label", fmt.Sprintf("mcs.owner=%s", req.OwnerName),
		"--label", fmt.Sprintf("mcs.java=%d", req.Java),
		imageFor(req.Java),
	)
	return args
}

// isBukkit : Paper et Spigot lisent spigot.yml (forwarding BungeeCord du proxy)
func isBukkit(t string) bool {
	switch mapServerType(t) {
	case "PAPER", "SPIGOT":
		return true
	}
	return false
}

// bungeecordReady : spigot.yml existe et le forwarding y est déjà activé
func bungeecordReady(serverPath string) bool {
	data, err := os.ReadFile(filepath.Join(serverPath, "spigot.yml"))
	if err != nil {
		return false
	}
	for _, l := range strings.Split(string(data), "\n") {
		if strings.TrimSpace(l) == "bungeecord: true" {
			return true
		}
	}
	return false
}

// firstStart : conteneur qui vient d'être lancé. Paper/Spigot : attente des
// fichiers de config, forwarding activé, redémarrage. Puis attente que le serveur
// soit prêt. Un problème de version de Java arrête le conteneur et le signale.
func firstStart(serverPath string, req CreateServerRequest) error {
	name := containerName(req.ServerID)
	if isBukkit(req.Type) && !bungeecordReady(serverPath) {
		log.Printf("Attente de la génération initiale des fichiers...")
		if err := waitForConfigFiles(serverPath, name, 120); err != nil {
			if je := javaFailure(name, err); je != err {
				return je
			}
			log.Printf("Avertissement : fichiers non générés à temps : %v", err)
		}
		if err := configureBungeecord(serverPath); err != nil {
			log.Printf("Avertissement : échec config bungeecord : %v", err)
		} else {
			log.Printf("Configuration bungeecord appliquée, redémarrage du serveur...")
			_ = exec.Command("docker", "restart", name).Run()
		}
	}
	if err := waitForMinecraftReady(name, 180); err != nil {
		err = javaFailure(name, err)
		log.Printf("Avertissement : %v", err)
		var je *javaError
		if errors.As(err, &je) {
			return je
		}
		return fmt.Errorf("le serveur n'a pas démarré à temps : %w", err)
	}
	return nil
}

// RecreateServer : nouveau conteneur pour un serveur existant (changement de
// version de Java), sans toucher à ses fichiers. start=false : créé mais arrêté.
func RecreateServer(dataPath string, req CreateServerRequest, start bool) error {
	log.Printf("Recréation du conteneur du serveur %d (Java %d, démarrage=%v)", req.ServerID, req.Java, start)
	serverPath := serverDataPath(dataPath, req.ServerID)
	if !fileExists(serverPath) {
		return fmt.Errorf("pas de données pour ce serveur sur cette machine")
	}
	if !isolationReady.Load() {
		if err := ensureIsolation(); err != nil {
			return fmt.Errorf("isolation réseau indisponible : %w", err)
		}
	}
	if start {
		if err := egressTunnelUp(); err != nil {
			return fmt.Errorf("démarrage refusé : %w", err)
		}
	}
	if err := ensureImage(imageFor(req.Java)); err != nil {
		return err
	}
	name := containerName(req.ServerID)
	_ = exec.Command("docker", "stop", "-t", "30", name).Run()
	if out, err := exec.Command("docker", "rm", name).CombinedOutput(); err != nil &&
		!strings.Contains(string(out), "No such container") {
		return fmt.Errorf("docker rm a échoué : %s", strings.TrimSpace(string(out)))
	}
	if !start {
		if out, err := exec.Command("docker", runArgs(serverPath, req, "create")...).CombinedOutput(); err != nil {
			return fmt.Errorf("docker create a échoué : %s", strings.TrimSpace(string(out)))
		}
		return nil
	}
	if out, err := exec.Command("docker", runArgs(serverPath, req, "run", "-d")...).CombinedOutput(); err != nil {
		return fmt.Errorf("docker run a échoué : %s", strings.TrimSpace(string(out)))
	}
	return firstStart(serverPath, req)
}

// Utilisateur non-root sous lequel tournent les serveurs (celui de l'image itzg)
const (
	containerUID = 1000
	containerGID = 1000
)

// javaHeapMb : part de la RAM du serveur donnée au tas Java (-Xms/-Xmx).
// La RAM choisie par le joueur est la limite TOTALE du conteneur ; la JVM a
// besoin de mémoire hors du tas (classes, code compilé, threads, tampons réseau,
// GC), prise dans cette RAM : 20 %, au moins 512 Mo, au plus 3 Go.
// Même calcul que l'API (ServerService.javaHeapMb).
func javaHeapMb(ramMb int) int {
	overhead := ramMb / 5
	if overhead < 512 {
		overhead = 512
	}
	if overhead > 3072 {
		overhead = 3072
	}
	heap := ramMb - overhead
	if heap < 256 {
		heap = 256
	}
	return heap
}

// hardeningArgs : réseau isolé et limites de sécurité pour un conteneur de serveur.
func hardeningArgs(ramMb int) []string {
	// Limite stricte = exactement la RAM choisie : rien n'est ajouté par-dessus
	containerMb := ramMb

	args := []string{
		"--network", mcsNetwork,
		"--user", fmt.Sprintf("%d:%d", containerUID, containerGID),
		"--cap-drop", "ALL",
		"--security-opt", "no-new-privileges:true",
		"--pids-limit", "2048",
		"--memory", fmt.Sprintf("%dm", containerMb),
		"--memory-swap", fmt.Sprintf("%dm", containerMb),
		"--log-driver", "json-file",
		"--log-opt", "max-size=10m",
		"--log-opt", "max-file=3",
	}
	for _, dns := range containerDNS {
		args = append(args, "--dns", dns)
	}
	return args
}

// waitForConfigFiles attend que server.properties et spigot.yml soient générés
// (abandon si le conteneur plante entre-temps)
func waitForConfigFiles(serverPath, container string, timeoutSeconds int) error {
	deadline := time.Now().Add(time.Duration(timeoutSeconds) * time.Second)

	for time.Now().Before(deadline) {
		if containerCrashed(container) {
			return fmt.Errorf("le serveur s'est arrêté pendant son premier démarrage")
		}
		propsExist := fileExists(filepath.Join(serverPath, "server.properties"))
		spigotExist := fileExists(filepath.Join(serverPath, "spigot.yml"))

		if propsExist && spigotExist {
			// Encore un peu pour que les fichiers soient complètement écrits
			time.Sleep(2 * time.Second)
			return nil
		}

		time.Sleep(2 * time.Second)
	}

	return fmt.Errorf("timeout après %d secondes", timeoutSeconds)
}

// fileExists vérifie l'existence d'un fichier
func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

// configureBungeecord modifie spigot.yml et server.properties pour le mode legacy
func configureBungeecord(serverPath string) error {
	// Modifier spigot.yml : settings.bungeecord = true
	spigotPath := filepath.Join(serverPath, "spigot.yml")
	if err := setYamlValue(spigotPath, "settings.bungeecord", "true"); err != nil {
		return fmt.Errorf("spigot.yml : %w", err)
	}

	// Modifier server.properties
	propsPath := filepath.Join(serverPath, "server.properties")
	if err := setPropertyValue(propsPath, "online-mode", "false"); err != nil {
		return fmt.Errorf("server.properties online-mode : %w", err)
	}
	if err := setPropertyValue(propsPath, "enforce-secure-profile", "false"); err != nil {
		return fmt.Errorf("server.properties enforce-secure-profile : %w", err)
	}
	if err := setPropertyValue(propsPath, "prevent-proxy-connections", "false"); err != nil {
		return fmt.Errorf("server.properties prevent-proxy-connections : %w", err)
	}

	return nil
}

// setPropertyValue modifie ou ajoute une ligne clé=valeur dans server.properties
func setPropertyValue(path, key, value string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}

	lines := strings.Split(string(data), "\n")
	found := false
	for i, line := range lines {
		if strings.HasPrefix(line, key+"=") {
			lines[i] = key + "=" + value
			found = true
			break
		}
	}

	if !found {
		lines = append(lines, key+"="+value)
	}

	return os.WriteFile(path, []byte(strings.Join(lines, "\n")), 0644)
}

// setYamlValue modifie une valeur dans un fichier YAML simple (sans préserver les commentaires)
// Approche simple : on cherche la ligne qui commence par la clé et on modifie sa valeur
func setYamlValue(path, dotPath, value string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}

	parts := strings.Split(dotPath, ".")
	if len(parts) < 2 {
		return fmt.Errorf("chemin YAML invalide : %s", dotPath)
	}

	lines := strings.Split(string(data), "\n")
	parent := parts[0]
	key := parts[len(parts)-1]

	insideParent := false
	parentIndent := -1

	for i, line := range lines {
		trimmed := strings.TrimSpace(line)
		indent := len(line) - len(strings.TrimLeft(line, " "))

		// On entre dans la section parent
		if strings.HasPrefix(trimmed, parent+":") && indent == 0 {
			insideParent = true
			parentIndent = indent
			continue
		}

		// On a quitté la section parent (nouvelle section au même niveau ou moins)
		if insideParent && trimmed != "" && indent <= parentIndent && !strings.HasPrefix(line, " ") {
			break
		}

		// On est dans la section parent et on cherche la clé
		if insideParent && strings.HasPrefix(trimmed, key+":") {
			lines[i] = strings.Repeat(" ", indent) + key + ": " + value
			return os.WriteFile(path, []byte(strings.Join(lines, "\n")), 0644)
		}
	}

	return fmt.Errorf("clé non trouvée : %s", dotPath)
}

// StartServer démarre un conteneur arrêté
func StartServer(serverID int64) error {
	log.Printf("Démarrage du serveur id=%d", serverID)

	out, err := exec.Command("docker", "start", containerName(serverID)).CombinedOutput()
	if err != nil {
		return fmt.Errorf("docker start a échoué : %s : %w", string(out), err)
	}

	if err := waitForMinecraftReady(containerName(serverID), 120); err != nil {
		err = javaFailure(containerName(serverID), err)
		log.Printf("Avertissement : %v", err)
		var je *javaError
		if errors.As(err, &je) {
			return je
		}
		return fmt.Errorf("le serveur n'a pas démarré à temps : %w", err)
	}

	return nil
}

// StopServer arrête un conteneur en cours
func StopServer(serverID int64) error {
	log.Printf("Arrêt du serveur id=%d", serverID)

	out, err := exec.Command("docker", "stop", containerName(serverID)).CombinedOutput()
	if err != nil {
		return fmt.Errorf("docker stop a échoué : %s : %w", string(out), err)
	}

	return nil
}

// DeleteServer supprime un conteneur et optionnellement ses données
func DeleteServer(dataPath string, serverID int64, deleteData bool) error {
	log.Printf("Suppression du serveur id=%d (deleteData=%v)", serverID, deleteData)

	// Stopper d'abord (ignore l'erreur si déjà arrêté)
	_ = StopServer(serverID)

	// Supprimer le conteneur (déjà absent = rien à faire)
	out, err := exec.Command("docker", "rm", containerName(serverID)).CombinedOutput()
	if err != nil && !strings.Contains(string(out), "No such container") {
		return fmt.Errorf("docker rm a échoué : %s : %w", string(out), err)
	}

	// Supprimer les données si demandé
	if deleteData {
		serverPath := serverDataPath(dataPath, serverID)
		if err := os.RemoveAll(serverPath); err != nil {
			return fmt.Errorf("suppression des données : %w", err)
		}
		log.Printf("Données du serveur %d supprimées", serverID)
		// Ses sauvegardes gardées sur la machine partent avec lui
		if err := os.RemoveAll(localRepoPath(dataPath, serverID)); err != nil {
			log.Printf("Sauvegardes locales du serveur %d : %v", serverID, err)
		}
	}

	return nil
}

// ServerStatus représente l'état d'un serveur
type ServerStatus struct {
	ServerID    int64  `json:"server_id"`
	State       string `json:"state"` // "running", "exited", "not_found", "created"
	Status      string `json:"status"`
	ContainerID string `json:"container_id"`
}

// GetServerStatus retourne l'état d'un serveur
func GetServerStatus(serverID int64) (*ServerStatus, error) {
	name := containerName(serverID)

	cmd := exec.Command("docker", "inspect", name, "--format", "{{.State.Status}}|{{.State.Running}}|{{.Id}}")
	out, err := cmd.CombinedOutput()

	if err != nil {
		// Si le conteneur n'existe pas, docker inspect retourne une erreur
		if strings.Contains(string(out), "No such") {
			return &ServerStatus{ServerID: serverID, State: "not_found"}, nil
		}
		return nil, fmt.Errorf("docker inspect : %w", err)
	}

	parts := strings.Split(strings.TrimSpace(string(out)), "|")
	if len(parts) < 3 {
		return nil, fmt.Errorf("réponse docker inspect invalide : %s", string(out))
	}

	return &ServerStatus{
		ServerID:    serverID,
		State:       parts[0],
		Status:      parts[0],
		ContainerID: parts[2],
	}, nil
}

// ListMcsContainers liste tous les conteneurs gérés par MCS
func ListMcsContainers() ([]map[string]interface{}, error) {
	cmd := exec.Command("docker", "ps", "-a", "--filter", "label=mcs.managed=true", "--format", "{{json .}}")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("docker ps : %s : %w", string(out), err)
	}

	var containers []map[string]interface{}
	for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		if line == "" {
			continue
		}
		var c map[string]interface{}
		if err := json.Unmarshal([]byte(line), &c); err == nil {
			containers = append(containers, c)
		}
	}

	return containers, nil
}

// containerExists vérifie si un conteneur portant un certain nom existe
func containerExists(name string) (bool, error) {
	cmd := exec.Command("docker", "ps", "-a", "--filter", "name=^/"+name+"$", "--format", "{{.Names}}")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return false, err
	}
	return strings.TrimSpace(string(out)) == name, nil
}

// mapServerType convertit notre type en celui attendu par itzg/minecraft-server
func mapServerType(t string) string {
	switch strings.ToUpper(t) {
	case "PAPER":
		return "PAPER"
	case "SPIGOT":
		return "SPIGOT"
	case "FORGE":
		return "FORGE"
	case "FABRIC":
		return "FABRIC"
	case "NEOFORGE":
		return "NEOFORGE"
	case "VANILLA":
		return "VANILLA"
	default:
		return "PAPER" // par défaut
	}
}
