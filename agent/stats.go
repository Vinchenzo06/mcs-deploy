package main

// Mesures des serveurs pour le panneau et /mcs info : CPU, RAM, réseau (toutes
// les 30 s, via "docker stats") et espace disque (toutes les 10 min, via "du").

import (
	"encoding/json"
	"fmt"
	"log"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	statsInterval = 30 * time.Second
	diskInterval  = 10 * time.Minute
)

type serverStats struct {
	ServerID   int64   `json:"server_id"`
	Running    bool    `json:"running"`
	CPUPercent float64 `json:"cpu_percent"`
	MemUsedMB  int64   `json:"mem_used_mb"`
	MemLimitMB int64   `json:"mem_limit_mb"`
	NetRxBytes int64   `json:"net_rx_bytes"`
	NetTxBytes int64   `json:"net_tx_bytes"`
	PIDs       int64   `json:"pids"`
	DiskUsedMB *int64  `json:"disk_used_mb,omitempty"`
}

var (
	diskMu    sync.Mutex
	diskCache = map[int64]int64{} // server_id -> Mo utilisés
	diskAt    time.Time
)

// parseSize convertit "512MiB", "1.5GB", "123kB", "0B" en octets
func parseSize(s string) int64 {
	s = strings.TrimSpace(s)
	units := []struct {
		suffix string
		mult   float64
	}{
		{"KiB", 1 << 10}, {"MiB", 1 << 20}, {"GiB", 1 << 30}, {"TiB", 1 << 40},
		{"kB", 1e3}, {"KB", 1e3}, {"MB", 1e6}, {"GB", 1e9}, {"TB", 1e12},
		{"B", 1},
	}
	for _, u := range units {
		if strings.HasSuffix(s, u.suffix) {
			v, err := strconv.ParseFloat(strings.TrimSpace(strings.TrimSuffix(s, u.suffix)), 64)
			if err != nil {
				return 0
			}
			return int64(v * u.mult)
		}
	}
	v, _ := strconv.ParseFloat(s, 64)
	return int64(v)
}

// parsePair : "12.3MiB / 1.5GiB" -> (octets, octets)
func parsePair(s string) (int64, int64) {
	parts := strings.SplitN(s, "/", 2)
	if len(parts) != 2 {
		return 0, 0
	}
	return parseSize(parts[0]), parseSize(parts[1])
}

type dockerStatsLine struct {
	Name     string `json:"Name"`
	CPUPerc  string `json:"CPUPerc"`
	MemUsage string `json:"MemUsage"`
	NetIO    string `json:"NetIO"`
	PIDs     string `json:"PIDs"`
}

func collectStats(dataPath string) ([]serverStats, error) {
	containers, err := listContainerStates()
	if err != nil {
		return nil, err
	}

	byName := map[string]*serverStats{}
	var running []string
	result := make([]serverStats, 0, len(containers))
	for _, c := range containers {
		result = append(result, serverStats{ServerID: c.ServerID, Running: c.State == "running"})
	}
	for i := range result {
		name := containerName(result[i].ServerID)
		byName[name] = &result[i]
		if result[i].Running {
			running = append(running, name)
		}
	}

	if len(running) > 0 {
		args := append([]string{"stats", "--no-stream", "--format", "{{json .}}"}, running...)
		out, err := exec.Command("docker", args...).Output()
		if err != nil {
			return nil, fmt.Errorf("docker stats : %w", err)
		}
		for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
			var l dockerStatsLine
			if json.Unmarshal([]byte(line), &l) != nil {
				continue
			}
			s := byName[l.Name]
			if s == nil {
				continue
			}
			s.CPUPercent, _ = strconv.ParseFloat(strings.TrimSuffix(l.CPUPerc, "%"), 64)
			used, limit := parsePair(l.MemUsage)
			s.MemUsedMB, s.MemLimitMB = used>>20, limit>>20
			s.NetRxBytes, s.NetTxBytes = parsePair(l.NetIO)
			s.PIDs, _ = strconv.ParseInt(strings.TrimSpace(l.PIDs), 10, 64)
		}
	}

	// Espace disque : mesure coûteuse, rafraîchie toutes les 10 minutes
	diskMu.Lock()
	if time.Since(diskAt) >= diskInterval {
		fresh := map[int64]int64{}
		for _, s := range result {
			out, err := exec.Command("du", "-sm", serverDataPath(dataPath, s.ServerID)).Output()
			if err != nil {
				continue
			}
			if f := strings.Fields(string(out)); len(f) > 0 {
				if mb, err := strconv.ParseInt(f[0], 10, 64); err == nil {
					fresh[s.ServerID] = mb
				}
			}
		}
		diskCache, diskAt = fresh, time.Now()
	}
	for i := range result {
		if mb, ok := diskCache[result[i].ServerID]; ok {
			v := mb
			result[i].DiskUsedMB = &v
		}
	}
	diskMu.Unlock()

	return result, nil
}

func sendStats(conn *websocket.Conn, dataPath string) {
	stats, err := collectStats(dataPath)
	if err != nil {
		log.Printf("Mesures : %v", err)
		return
	}
	if err := writeJSON(conn, map[string]interface{}{"type": "server_stats", "servers": stats}); err != nil {
		log.Printf("Envoi des mesures : %v", err)
	}
}
