package main

// Forwarding du proxy pour les serveurs moddés (lot 36).
//
// Paper/Spigot lisent le forwarding BungeeCord de spigot.yml. Fabric, Forge et
// NeoForge n'ont rien de tel : sans mod, ils ne voient pas le vrai compte des
// joueurs (UUID « offline », pas de skins). L'agent fait installer par l'image
// itzg (Modrinth) le bon mod et écrit sa config avant chaque démarrage :
//   - Fabric 1.16.5+ : FabricProxy-Lite (forwarding "modern", secret Velocity) ;
//   - Forge 1.13+ et NeoForge : Proxy-Compatible-Forge en "modern" ;
//   - Forge 1.7–1.12 : Proxy-Compatible-Forge en "legacy" (+ MixinBooter/UniMixins).
// Le mode vient de l'API ; le propriétaire ne peut pas le changer durablement
// (config réécrite à chaque démarrage).

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

// needsForwardingMods : ce serveur a besoin d'un mod pour recevoir le forwarding
func needsForwardingMods(req CreateServerRequest) bool {
	if req.Forwarding != "modern" && req.Forwarding != "legacy" {
		return false
	}
	switch mapServerType(req.Type) {
	case "FABRIC", "FORGE", "NEOFORGE":
		return true
	}
	return false
}

// mcMinor : 12 pour "1.12.2", 99 pour "LATEST" ou "26.1"
func mcMinor(version string) (int, int) {
	parts := strings.Split(version, ".")
	if len(parts) < 2 || parts[0] != "1" {
		return 99, 0
	}
	minor, err := strconv.Atoi(parts[1])
	if err != nil {
		return 99, 0
	}
	patch := 0
	if len(parts) > 2 {
		patch, _ = strconv.Atoi(parts[2])
	}
	return minor, patch
}

// forwardingMods : projets Modrinth à installer (variable MODRINTH_PROJECTS d'itzg)
func forwardingMods(req CreateServerRequest) []string {
	if !needsForwardingMods(req) {
		return nil
	}
	minor, patch := mcMinor(req.Version)
	switch mapServerType(req.Type) {
	case "FABRIC":
		return []string{"fabricproxy-lite"}
	case "NEOFORGE":
		return []string{"proxy-compatible-forge"}
	}
	// Forge : PCF et le chargeur de mixins demandé selon la version
	mods := []string{"proxy-compatible-forge"}
	switch {
	case minor <= 7:
		mods = append(mods, "unimixins")
	case minor <= 12:
		mods = append(mods, "mixinbooter")
	case minor == 13:
		mods = append(mods, "modernmixins")
	case minor == 14, minor == 15 && patch <= 1, minor == 16 && patch <= 1:
		mods = append(mods, "mixinbootstrap")
	}
	return mods
}

// forwardingArgs : variables et étiquette du conteneur pour le forwarding
func forwardingArgs(req CreateServerRequest) []string {
	args := []string{"--label", "mcs.fwd=" + req.Forwarding}
	if mods := forwardingMods(req); len(mods) > 0 {
		args = append(args,
			"-e", "MODRINTH_PROJECTS="+strings.Join(mods, ","),
			"-e", "MODRINTH_DOWNLOAD_DEPENDENCIES=required",
		)
	}
	return args
}

// containerForwarding : mode avec lequel le conteneur a été créé ("" = avant le lot 36)
func containerForwarding(serverID int64) string {
	out, err := exec.Command("docker", "inspect", "-f", `{{index .Config.Labels "mcs.fwd"}}`,
		containerName(serverID)).Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

// ensureForwardingConfig écrit la config du mod de forwarding avant un démarrage
func ensureForwardingConfig(serverPath string, req CreateServerRequest) {
	if !needsForwardingMods(req) {
		return
	}
	dir := filepath.Join(serverPath, "config")
	if err := os.MkdirAll(dir, 0755); err != nil {
		log.Printf("Forwarding du serveur %d : %v", req.ServerID, err)
		return
	}
	_ = os.Chown(dir, containerUID, containerGID)
	mode := strings.ToUpper(req.Forwarding)
	var err error
	switch mapServerType(req.Type) {
	case "FABRIC":
		err = setTomlKeys(filepath.Join(dir, "FabricProxy-Lite.toml"), "", map[string]string{
			"secret": tomlString(req.ForwardingSecret),
		})
	default:
		if minor, _ := mcMinor(req.Version); minor <= 12 {
			err = setForgeCfg(filepath.Join(dir, "proxy-compatible-forge.cfg"), map[string]string{
				"B:enabled": "true",
				"S:mode":    mode,
				"S:secret":  req.ForwardingSecret,
			})
		} else {
			err = setTomlKeys(filepath.Join(dir, "proxy-compatible-forge.toml"), "forwarding", map[string]string{
				"enabled": "true",
				"mode":    tomlString(mode),
				"secret":  tomlString(req.ForwardingSecret),
			})
		}
	}
	if err != nil {
		log.Printf("Config du forwarding du serveur %d : %v", req.ServerID, err)
	}
}

func tomlString(s string) string {
	return `"` + strings.NewReplacer(`\`, `\\`, `"`, `\"`).Replace(s) + `"`
}

// setTomlKeys remplace (ou ajoute) des clés d'un fichier TOML simple, dans une
// section ("" = haut du fichier) ; le reste du fichier est gardé tel quel
func setTomlKeys(path, section string, values map[string]string) error {
	data, _ := os.ReadFile(path)
	lines := strings.Split(strings.TrimRight(string(data), "\n"), "\n")
	if len(data) == 0 {
		lines = nil
	}
	cur := ""
	done := map[string]bool{}
	sectionEnd := -1 // indice où insérer les clés manquantes
	header := regexp.MustCompile(`^\s*\[([^\]]+)\]\s*$`)
	for i, l := range lines {
		if m := header.FindStringSubmatch(l); m != nil {
			if cur == section && sectionEnd < 0 {
				sectionEnd = i
			}
			cur = strings.TrimSpace(m[1])
			continue
		}
		if cur != section {
			continue
		}
		k := strings.TrimSpace(strings.SplitN(l, "=", 2)[0])
		if v, ok := values[k]; ok && strings.Contains(l, "=") {
			indent := l[:len(l)-len(strings.TrimLeft(l, " \t"))]
			lines[i] = indent + k + " = " + v
			done[k] = true
		}
	}
	var missing []string
	for k, v := range values {
		if !done[k] {
			missing = append(missing, k+" = "+v)
		}
	}
	if len(missing) > 0 {
		switch {
		case section == "":
			// clés de haut niveau : avant la première section
			first := len(lines)
			for i, l := range lines {
				if header.MatchString(l) {
					first = i
					break
				}
			}
			lines = append(lines[:first], append(missing, lines[first:]...)...)
		case cur == section || sectionEnd >= 0:
			at := len(lines)
			if cur != section && sectionEnd >= 0 {
				at = sectionEnd
			}
			lines = append(lines[:at], append(missing, lines[at:]...)...)
		default:
			lines = append(lines, "", "["+section+"]")
			lines = append(lines, missing...)
		}
	}
	return writeOwned(path, strings.Join(lines, "\n")+"\n")
}

// setForgeCfg : fichier de config Forge 1.12 (« forwarding { S:mode=LEGACY } »)
func setForgeCfg(path string, values map[string]string) error {
	data, _ := os.ReadFile(path)
	text := string(data)
	if !strings.Contains(text, "forwarding {") {
		var b strings.Builder
		b.WriteString("# Configuration file\n\nforwarding {\n")
		for k, v := range values {
			fmt.Fprintf(&b, "    %s=%s\n", k, v)
		}
		b.WriteString("}\n\n")
		return writeOwned(path, b.String()+text)
	}
	lines := strings.Split(text, "\n")
	in := false
	done := map[string]bool{}
	end := -1
	for i, l := range lines {
		t := strings.TrimSpace(l)
		if strings.HasPrefix(t, "forwarding {") {
			in = true
			continue
		}
		if in && t == "}" {
			end = i
			break
		}
		if in {
			k := strings.SplitN(t, "=", 2)[0]
			if v, ok := values[k]; ok {
				lines[i] = "    " + k + "=" + v
				done[k] = true
			}
		}
	}
	if end >= 0 {
		var missing []string
		for k, v := range values {
			if !done[k] {
				missing = append(missing, "    "+k+"="+v)
			}
		}
		lines = append(lines[:end], append(missing, lines[end:]...)...)
	}
	return writeOwned(path, strings.Join(lines, "\n"))
}

func writeOwned(path, content string) error {
	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		return err
	}
	_ = os.Chown(path, containerUID, containerGID)
	return nil
}
