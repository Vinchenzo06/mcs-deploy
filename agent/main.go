package main

import (
	"errors"
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"
)

// Version de l'agent (fait foi, indépendamment du fichier de config)
const AgentVersion = "0.2.0"

// Vérifie régulièrement que l'isolation réseau est toujours en place
// (un redémarrage de Docker ou un rechargement du pare-feu peut l'effacer).
func watchIsolation(stop <-chan struct{}) {
	ticker := time.NewTicker(2 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
			if err := ensureIsolation(); err != nil {
				log.Printf("ATTENTION : isolation réseau : %v", err)
			}
		}
	}
}

func main() {
	configPath := flag.String("config", "config.yaml", "Chemin du fichier de config")
	flag.Parse()

	config, err := loadConfig(*configPath)
	if err != nil {
		log.Fatalf("Erreur de chargement de la config : %v", err)
	}

	config.Node.AgentVersion = AgentVersion
	log.Printf("MCS Agent v%s démarrage...", AgentVersion)
	log.Printf("Connexion à l'API : %s", config.API.URL)
	log.Printf("Dossier de données : %s", config.Docker.DataPath)

	interrupt := make(chan os.Signal, 1)
	signal.Notify(interrupt, os.Interrupt, syscall.SIGTERM)

	stopChan := make(chan struct{})
	go func() {
		<-interrupt
		log.Println("Arrêt global demandé...")
		close(stopChan)
	}()

	// Isolation réseau des serveurs : sans elle, aucune création n'est acceptée
	if err := ensureIsolation(); err != nil {
		log.Printf("ATTENTION : isolation réseau impossible, les créations de serveur seront refusées : %v", err)
	}
	go watchIsolation(stopChan)

	for {
		select {
		case <-stopChan:
			log.Println("Agent arrêté")
			return
		default:
			err := runConnection(config, stopChan)
			if err != nil {
				delay := 5 * time.Second
				if errors.Is(err, errAuth) {
					// Jeton refusé (machine révoquée ?) : inutile d'insister
					delay = 60 * time.Second
				}
				log.Printf("Connexion fermée : %v. Reconnexion dans %s...", err, delay)
				select {
				case <-time.After(delay):
				case <-stopChan:
					log.Println("Agent arrêté")
					return
				}
			}
		}
	}
}
