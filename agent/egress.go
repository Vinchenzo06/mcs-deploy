package main

// Sortie Internet des serveurs via le VPS (cacher l'IP du volontaire).
//
// node-setup.sh configure un tunnel WireGuard "wg-mcs" vers le VPS (wg-quick,
// "Table = off" : le trafic de la machine elle-même n'est pas touché, et
// "PostUp" ajoute la route par défaut via wg-mcs dans la table 51820).
//
// L'agent ajoute une règle de routage : tout ce qui sort du pont des serveurs
// (mcs-br0) est routé par la table 51820, qui contient :
//   - la route par défaut via wg-mcs (présente seulement quand le tunnel est actif),
//   - une route "blackhole" de secours, posée par l'agent.
// Si le tunnel tombe, les serveurs n'ont plus Internet, mais ils ne sortent
// jamais par la connexion du volontaire (coupe-circuit). Les réponses aux
// connexions entrantes (tunnel rathole via 127.0.0.1) visent une adresse locale
// et passent par la table "local", évaluée avant cette règle.

import (
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

const (
	egressIface        = "wg-mcs"
	egressTable        = "51820"
	egressRulePriority = "1000"
	egressBlackhole    = "4000"          // métrique de la route de secours
	egressMaxHandshake = 5 * time.Minute // au-delà, le tunnel est considéré inactif
)

// egressMode : "vps" (sortie via le VPS) ou "" (sortie directe, ancien mode).
var egressMode string

func ipCmd(args ...string) (string, error) {
	out, err := exec.Command("ip", args...).CombinedOutput()
	return strings.TrimSpace(string(out)), err
}

// ensureEgress pose (ou retire) la règle de routage et le coupe-circuit.
func ensureEgress() error {
	rules, err := ipCmd("-4", "rule", "show")
	if err != nil {
		return fmt.Errorf("ip rule show : %s", rules)
	}
	// Une règle posée avant la création du pont s'affiche "iif mcs-br0 [detached]"
	hasRule := false
	for _, line := range strings.Split(rules, "\n") {
		if strings.Contains(line, "iif "+mcsBridge+" ") && strings.Contains(line, "lookup "+egressTable) {
			hasRule = true
			break
		}
	}

	if egressMode != "vps" {
		if hasRule {
			if out, err := ipCmd("-4", "rule", "del", "iif", mcsBridge, "lookup", egressTable); err != nil {
				return fmt.Errorf("ip rule del : %s", out)
			}
		}
		return nil
	}

	// Coupe-circuit d'abord, règle ensuite : jamais de fenêtre sans protection
	routes, _ := ipCmd("-4", "route", "show", "table", egressTable)
	if !strings.Contains(routes, "blackhole default") {
		if out, err := ipCmd("-4", "route", "replace", "blackhole", "default",
			"metric", egressBlackhole, "table", egressTable); err != nil {
			return fmt.Errorf("route blackhole : %s", out)
		}
	}
	if !hasRule {
		if out, err := ipCmd("-4", "rule", "add", "iif", mcsBridge, "lookup", egressTable,
			"priority", egressRulePriority); err != nil {
			return fmt.Errorf("ip rule add : %s", out)
		}
	}
	return nil
}

// egressTunnelUp vérifie que le tunnel vers le VPS fonctionne réellement.
func egressTunnelUp() error {
	if egressMode != "vps" {
		return nil
	}
	routes, _ := ipCmd("-4", "route", "show", "table", egressTable)
	if !strings.Contains(routes, "default dev "+egressIface) {
		return fmt.Errorf("tunnel %s inactif (route absente) : systemctl status wg-quick@%s", egressIface, egressIface)
	}
	out, err := exec.Command("wg", "show", egressIface, "latest-handshakes").Output()
	if err != nil {
		return fmt.Errorf("tunnel %s absent : systemctl status wg-quick@%s", egressIface, egressIface)
	}
	fields := strings.Fields(string(out))
	if len(fields) < 2 {
		return fmt.Errorf("tunnel %s sans pair", egressIface)
	}
	ts, _ := strconv.ParseInt(fields[1], 10, 64)
	if ts == 0 {
		return fmt.Errorf("tunnel %s : aucun échange avec le VPS (port UDP bloqué ?)", egressIface)
	}
	if age := time.Since(time.Unix(ts, 0)); age > egressMaxHandshake {
		return fmt.Errorf("tunnel %s : dernier échange avec le VPS il y a %s", egressIface, age.Round(time.Second))
	}
	return nil
}
