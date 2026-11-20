package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"io"
	"io/fs"
	"log"
	"os"
	"regexp"
	"strings"
)

// Plugins de la team IMG (lot 46) : avec chaque démarrage d'un serveur marqué « team »
// (sudo mcs-team sur le VPS), l'API envoie les jars de la team (ex. img-farm). L'agent les
// pose dans plugins/, les remplace s'ils ont changé, et retire ceux qu'il avait posés et
// qui ne sont plus envoyés (liste dans plugins/.mcs-team-plugins).
//
// Le dossier du serveur appartient au conteneur : tout passe par os.Root, qui refuse de
// suivre un lien symbolique hors du dossier (l'agent tourne en root).

type teamPlugin struct {
	Name   string `json:"name"`
	Sha256 string `json:"sha256"`
	Data   string `json:"data"`
}

const teamPluginsList = ".mcs-team-plugins"

var teamPluginName = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}\.jar$`)

func fileSha256(root *os.Root, name string) string {
	f, err := root.Open(name)
	if err != nil {
		return ""
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return ""
	}
	return hex.EncodeToString(h.Sum(nil))
}

// syncTeamPlugins : wanted nil = l'API n'a rien envoyé (ancienne API, dossier illisible) : rien ne change
func syncTeamPlugins(serverPath string, serverID int64, wanted *[]teamPlugin) {
	if wanted == nil {
		return
	}
	root, err := os.OpenRoot(serverPath)
	if err != nil {
		log.Printf("Plugins de la team, serveur %d : %v", serverID, err)
		return
	}
	defer root.Close()

	info, err := root.Lstat("plugins")
	switch {
	case errors.Is(err, fs.ErrNotExist):
		if len(*wanted) == 0 {
			return
		}
		if err := root.Mkdir("plugins", 0755); err != nil {
			log.Printf("Plugins de la team, serveur %d : %v", serverID, err)
			return
		}
		if d, err := root.Open("plugins"); err == nil {
			_ = d.Chown(containerUID, containerGID)
			d.Close()
		}
	case err != nil:
		log.Printf("Plugins de la team, serveur %d : %v", serverID, err)
		return
	case !info.IsDir():
		log.Printf("Plugins de la team, serveur %d : plugins/ n'est pas un dossier, ignoré", serverID)
		return
	}
	dir, err := root.OpenRoot("plugins")
	if err != nil {
		log.Printf("Plugins de la team, serveur %d : %v", serverID, err)
		return
	}
	defer dir.Close()

	// Plugins posés par MCS au démarrage précédent
	previous := map[string]bool{}
	if f, err := dir.Open(teamPluginsList); err == nil {
		sc := bufio.NewScanner(io.LimitReader(f, 64*1024))
		for sc.Scan() {
			if name := strings.TrimSpace(sc.Text()); teamPluginName.MatchString(name) {
				previous[name] = true
			}
		}
		f.Close()
	}

	var names []string
	for _, p := range *wanted {
		if !teamPluginName.MatchString(p.Name) {
			log.Printf("Plugins de la team, serveur %d : nom refusé %q", serverID, p.Name)
			continue
		}
		data, err := base64.StdEncoding.DecodeString(p.Data)
		if err != nil {
			log.Printf("Plugins de la team, serveur %d : %s illisible", serverID, p.Name)
			continue
		}
		sum := sha256.Sum256(data)
		if hex.EncodeToString(sum[:]) != strings.ToLower(p.Sha256) {
			log.Printf("Plugins de la team, serveur %d : empreinte de %s incorrecte, ignoré", serverID, p.Name)
			continue
		}
		names = append(names, p.Name)
		delete(previous, p.Name)
		if st, err := dir.Lstat(p.Name); err == nil && st.Mode().IsRegular() && fileSha256(dir, p.Name) == p.Sha256 {
			continue // déjà à jour
		} else if err == nil && !st.Mode().IsRegular() {
			_ = dir.Remove(p.Name) // lien ou dossier à la place du jar
		}
		if err := writeInRoot(dir, p.Name, data); err != nil {
			log.Printf("Plugins de la team, serveur %d : %s : %v", serverID, p.Name, err)
			continue
		}
		log.Printf("Serveur %d : plugin de la team %s installé ou mis à jour", serverID, p.Name)
	}

	// Retirés de la team (ou serveur sorti de la team)
	for name := range previous {
		if st, err := dir.Lstat(name); err == nil && !st.IsDir() {
			if err := dir.Remove(name); err == nil {
				log.Printf("Serveur %d : plugin de la team %s retiré", serverID, name)
			}
		}
	}

	if len(names) == 0 {
		_ = dir.Remove(teamPluginsList)
		return
	}
	if err := writeInRoot(dir, teamPluginsList, []byte(strings.Join(names, "\n")+"\n")); err != nil {
		log.Printf("Plugins de la team, serveur %d : %v", serverID, err)
	}
}

// writeInRoot écrit un fichier (propriétaire : l'utilisateur du conteneur), sans sortir du dossier
func writeInRoot(root *os.Root, name string, data []byte) error {
	f, err := root.OpenFile(name, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0644)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, bytes.NewReader(data)); err != nil {
		f.Close()
		return err
	}
	_ = f.Chown(containerUID, containerGID)
	return f.Close()
}
