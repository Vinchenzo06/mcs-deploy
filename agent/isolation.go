package main

// Isolation réseau des serveurs joueurs.
//
// Les joueurs peuvent installer des plugins et des mods, donc exécuter du code
// arbitraire dans leur conteneur. Un serveur ne doit pouvoir joindre :
//   - ni les autres serveurs (le forwarding legacy de Velocity n'a pas de
//     secret : joindre un autre serveur en direct permettrait d'y usurper un op),
//   - ni la machine hôte (SSH, services locaux du volontaire),
//   - ni le réseau local du volontaire (box, NAS, autres PC).
// Internet reste accessible (téléchargement de Paper, des mods, etc.).
//
// Mise en œuvre : tous les serveurs sont sur un réseau Docker dédié dont le
// pont s'appelle mcs-br0 (communication entre conteneurs désactivée), et
// l'agent pose ses propres chaînes iptables, accrochées à DOCKER-USER (ou
// FORWARD) et à INPUT. Elles sont vérifiées régulièrement et reposées si
// elles ont disparu (redémarrage de Docker, pare-feu rechargé...).

import (
	"fmt"
	"log"
	"os/exec"
	"strings"
	"sync"
	"sync/atomic"
)

const (
	mcsNetwork   = "mcs-servers"
	mcsBridge    = "mcs-br0"
	chainForward = "MCS-ISOLATION"
	chainInput   = "MCS-ISOLATION-IN"
)

// Destinations interdites aux serveurs : réseaux privés, CGNAT, lien local
// (dont les métadonnées cloud), multicast et plages réservées.
var blockedRanges = []string{
	"10.0.0.0/8",
	"172.16.0.0/12",
	"192.168.0.0/16",
	"100.64.0.0/10",
	"169.254.0.0/16",
	"224.0.0.0/4",
	"240.0.0.0/4",
}

// Résolveurs DNS publics donnés aux conteneurs : sans eux, Docker utiliserait
// ceux de l'hôte, souvent la box du volontaire, que le pare-feu bloque.
var containerDNS = []string{"1.1.1.1", "9.9.9.9"}

var (
	isolationMu    sync.Mutex
	isolationReady atomic.Bool
)

// ensureIsolation crée le réseau et pose le pare-feu si nécessaire.
func ensureIsolation() error {
	isolationMu.Lock()
	defer isolationMu.Unlock()

	if err := ensureNetwork(); err != nil {
		isolationReady.Store(false)
		return fmt.Errorf("réseau Docker %s : %w", mcsNetwork, err)
	}
	if !firewallHealthy() {
		if err := applyFirewall(); err != nil {
			isolationReady.Store(false)
			return fmt.Errorf("pare-feu : %w", err)
		}
		log.Printf("Isolation réseau appliquée (réseau %s, pont %s)", mcsNetwork, mcsBridge)
	}
	isolationReady.Store(true)
	return nil
}

func ensureNetwork() error {
	out, err := exec.Command("docker", "network", "inspect", mcsNetwork,
		"--format", `{{index .Options "com.docker.network.bridge.name"}}`).CombinedOutput()
	if err == nil {
		if strings.TrimSpace(string(out)) != mcsBridge {
			return fmt.Errorf("le réseau existe mais son pont n'est pas %s : supprime-le (docker network rm %s)",
				mcsBridge, mcsNetwork)
		}
		return nil
	}

	out, err = exec.Command("docker", "network", "create",
		"--driver", "bridge",
		"--opt", "com.docker.network.bridge.name="+mcsBridge,
		"--opt", "com.docker.network.bridge.enable_icc=false",
		"--label", "mcs.managed=true",
		mcsNetwork).CombinedOutput()
	if err != nil {
		return fmt.Errorf("docker network create : %s : %w", strings.TrimSpace(string(out)), err)
	}
	log.Printf("Réseau Docker %s créé", mcsNetwork)
	return nil
}

// Règles attendues dans chaque chaîne (format de "iptables -S", sans l'en-tête -N).
func expectedRules() map[string][]string {
	fwd := []string{
		"-A " + chainForward + " -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN",
		"-A " + chainForward + " -i " + mcsBridge + " -o " + mcsBridge + " -j DROP",
	}
	for _, r := range blockedRanges {
		fwd = append(fwd, "-A "+chainForward+" -d "+r+" -i "+mcsBridge+" -j DROP")
	}
	in := []string{
		"-A " + chainInput + " -i " + mcsBridge + " -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN",
		"-A " + chainInput + " -i " + mcsBridge + " -j DROP",
	}
	return map[string][]string{chainForward: fwd, chainInput: in}
}

func iptables(args ...string) (string, error) {
	out, err := exec.Command("iptables", append([]string{"-w"}, args...)...).CombinedOutput()
	return strings.TrimSpace(string(out)), err
}

// Chaîne de Docker à laquelle accrocher nos règles de transit. DOCKER-USER est
// évaluée par Docker avant ses propres règles. Si elle n'existe pas (Docker en
// mode nftables), on s'accroche directement à FORWARD : un DROP y reste définitif.
func forwardParent() string {
	if _, err := iptables("-n", "-L", "DOCKER-USER"); err == nil {
		return "DOCKER-USER"
	}
	return "FORWARD"
}

func firewallHealthy() bool {
	for chain, want := range expectedRules() {
		out, err := iptables("-S", chain)
		if err != nil {
			return false
		}
		var got []string
		for _, line := range strings.Split(out, "\n") {
			if strings.HasPrefix(line, "-A ") {
				got = append(got, line)
			}
		}
		if len(got) != len(want) {
			return false
		}
		for i := range want {
			if got[i] != want[i] {
				return false
			}
		}
	}
	if _, err := iptables("-C", forwardParent(), "-j", chainForward); err != nil {
		return false
	}
	if _, err := iptables("-C", "INPUT", "-j", chainInput); err != nil {
		return false
	}
	return true
}

func applyFirewall() error {
	rules := expectedRules()
	for _, chain := range []string{chainForward, chainInput} {
		if _, err := iptables("-n", "-L", chain); err != nil {
			if out, err := iptables("-N", chain); err != nil {
				return fmt.Errorf("iptables -N %s : %s", chain, out)
			}
		}
		if out, err := iptables("-F", chain); err != nil {
			return fmt.Errorf("iptables -F %s : %s", chain, out)
		}
		for _, rule := range rules[chain] {
			args := strings.Fields(rule)
			if out, err := iptables(args...); err != nil {
				return fmt.Errorf("iptables %s : %s", rule, out)
			}
		}
	}
	for parent, chain := range map[string]string{forwardParent(): chainForward, "INPUT": chainInput} {
		if _, err := iptables("-C", parent, "-j", chain); err != nil {
			if out, err := iptables("-I", parent, "1", "-j", chain); err != nil {
				return fmt.Errorf("iptables -I %s : %s", parent, out)
			}
		}
	}
	return nil
}
