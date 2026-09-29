package main

import (
	"os"

	"gopkg.in/yaml.v3"
)

type Config struct {
	API struct {
		URL string `yaml:"url"`
	} `yaml:"api"`
	Node struct {
		Token        string `yaml:"token"`
		AgentVersion string `yaml:"agent_version"`
	} `yaml:"node"`
	Heartbeat struct {
		IntervalSeconds int `yaml:"interval_seconds"`
	} `yaml:"heartbeat"`
	Docker struct {
		DataPath string `yaml:"data_path"`
	} `yaml:"docker"`
	// Part de la machine prêtée à MCS (0 = moitié de la ressource)
	Capacity Capacity `yaml:"capacity"`
	// Sauvegardes des serveurs gardées sur cette machine (choix du volontaire,
	// node-setup.sh --capacity) ; elles comptent dans le quota disque de chaque serveur
	Backups struct {
		Local bool `yaml:"local"`
	} `yaml:"backups"`
	Network struct {
		// "vps" : les serveurs sortent sur Internet par le tunnel WireGuard du VPS
		Egress string `yaml:"egress"`
	} `yaml:"network"`
}

func loadConfig(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	config := &Config{}
	if err := yaml.Unmarshal(data, config); err != nil {
		return nil, err
	}

	// Valeurs par défaut
	if config.Docker.DataPath == "" {
		config.Docker.DataPath = "/opt/mcs-data/servers"
	}

	return config, nil
}
