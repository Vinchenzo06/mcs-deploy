package main

// Ressources de la machine et part que le volontaire donne à MCS.
//
// La capacité (config.yaml, section "capacity") est ce que le volontaire prête :
// l'API ne place pas plus de serveurs que ça sur la machine. Sans réglage,
// l'agent propose la moitié de la RAM, la moitié des cœurs, et la moitié de
// l'espace disque disponible pour les serveurs (libre + déjà utilisé par MCS).

import (
	"bufio"
	"log"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"syscall"
)

type Capacity struct {
	RAMMB    int64 `json:"ram_mb" yaml:"ram_mb"`
	CPUCores int64 `json:"cpu_cores" yaml:"cpu_cores"`
	DiskMB   int64 `json:"disk_mb" yaml:"disk_mb"`
}

type HostInfo struct {
	RAMTotalMB  int64 `json:"ram_total_mb"`
	RAMAvailMB  int64 `json:"ram_available_mb"`
	CPUCores    int64 `json:"cpu_cores"`
	DiskTotalMB int64 `json:"disk_total_mb"`
	DiskFreeMB  int64 `json:"disk_free_mb"`
	DataUsedMB  int64 `json:"data_used_mb"` // espace déjà occupé par les serveurs MCS
}

// dataUsedMB : espace occupé par les données des serveurs (du -sm)
func dataUsedMB(path string) int64 {
	out, err := exec.Command("du", "-sm", path).Output()
	if err != nil {
		return 0
	}
	if f := strings.Fields(string(out)); len(f) > 0 {
		v, _ := strconv.ParseInt(f[0], 10, 64)
		return v
	}
	return 0
}

// memInfo : (total, disponible) en Mo, depuis /proc/meminfo
func memInfo() (int64, int64) {
	f, err := os.Open("/proc/meminfo")
	if err != nil {
		return 0, 0
	}
	defer f.Close()
	var total, avail int64
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 2 {
			continue
		}
		kb, _ := strconv.ParseInt(fields[1], 10, 64)
		switch fields[0] {
		case "MemTotal:":
			total = kb / 1024
		case "MemAvailable:":
			avail = kb / 1024
		}
	}
	return total, avail
}

// diskInfo : (total, libre) en Mo pour la partition qui contient path
func diskInfo(path string) (int64, int64) {
	_ = os.MkdirAll(path, 0755)
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err != nil {
		return 0, 0
	}
	bs := int64(st.Bsize)
	return int64(st.Blocks) * bs >> 20, int64(st.Bavail) * bs >> 20
}

func hostInfo(dataPath string) HostInfo {
	total, avail := memInfo()
	dTotal, dFree := diskInfo(dataPath)
	return HostInfo{
		RAMTotalMB:  total,
		RAMAvailMB:  avail,
		CPUCores:    int64(runtime.NumCPU()),
		DiskTotalMB: dTotal,
		DiskFreeMB:  dFree,
	}
}

// effectiveCapacity : réglage du volontaire, complété par des valeurs par défaut
// (la moitié de chaque ressource) et plafonné pour que le volontaire garde
// toujours au moins 10 % de sa RAM, 10 % de son disque et un cœur CPU
// (mêmes règles que node-setup.sh, appliquées même si config.yaml est modifié)
func effectiveCapacity(cfg Capacity, host HostInfo) Capacity {
	c := cfg
	if c.RAMMB <= 0 {
		c.RAMMB = host.RAMTotalMB / 2
	}
	if c.CPUCores <= 0 {
		c.CPUCores = host.CPUCores / 2
		if c.CPUCores < 1 {
			c.CPUCores = 1
		}
	}
	if c.DiskMB <= 0 {
		c.DiskMB = (host.DiskFreeMB + host.DataUsedMB) / 2
	}
	if max := host.RAMTotalMB * 90 / 100; host.RAMTotalMB > 0 && c.RAMMB > max {
		log.Printf("Capacité RAM ramenée de %d à %d Mo (10 %% doivent rester au volontaire)", c.RAMMB, max)
		c.RAMMB = max
	}
	if max := host.CPUCores - 1; host.CPUCores > 1 && c.CPUCores > max {
		log.Printf("Capacité CPU ramenée de %d à %d cœurs (un cœur reste au volontaire)", c.CPUCores, max)
		c.CPUCores = max
	}
	if max := host.DiskFreeMB + host.DataUsedMB - host.DiskTotalMB/10; host.DiskTotalMB > 0 && c.DiskMB > max {
		if max < 0 {
			max = 0
		}
		log.Printf("Capacité disque ramenée de %d à %d Mo (10 %% du disque doivent rester libres)", c.DiskMB, max)
		c.DiskMB = max
	}
	return c
}
