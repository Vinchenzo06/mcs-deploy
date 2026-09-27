package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	configPath := flag.String("config", "config.yaml", "Chemin du fichier de config")
	flag.Parse()

	config, err := loadConfig(*configPath)
	if err != nil {
		log.Fatalf("Erreur de chargement de la config : %v", err)
	}

	log.Printf("MCS Agent v%s démarrage...", config.Node.AgentVersion)
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

	for {
		select {
		case <-stopChan:
			log.Println("Agent arrêté")
			return
		default:
			err := runConnection(config, stopChan)
			if err != nil {
				log.Printf("Connexion fermée : %v. Reconnexion dans 5 secondes...", err)
				select {
				case <-time.After(5 * time.Second):
				case <-stopChan:
					log.Println("Agent arrêté")
					return
				}
			}
		}
	}
}
