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
const AgentVersion = "0.16.1"

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
	early := flag.Bool("early", false, "Pose les protections réseau (pare-feu, coupe-circuit) puis quitte : lancé au démarrage, avant Docker")
	flag.Parse()

	config, err := loadConfig(*configPath)
	if err != nil {
		log.Fatalf("Erreur de chargement de la config : %v", err)
	}

	if *early {
		egressMode = config.Network.Egress
		if err := applyEarlyProtections(); err != nil {
			log.Fatalf("Protections réseau : %v", err)
		}
		log.Printf("Protections réseau posées (pare-feu MCS, coupe-circuit de sortie : %v)", egressMode == "vps")
		return
	}

	config.Node.AgentVersion = AgentVersion
	log.Printf("MCS Agent v%s démarrage...", AgentVersion)
	log.Printf("Connexion à l'API : %s", config.API.URL)
	log.Printf("Dossier de données : %s", config.Docker.DataPath)
	localBackupsStartup(config)

	egressMode = config.Network.Egress
	if egressMode == "vps" {
		log.Printf("Sortie Internet des serveurs : via le VPS (tunnel %s, coupe-circuit actif)", egressIface)
	} else {
		log.Printf("ATTENTION : sortie Internet des serveurs directe (IP de cette machine visible). Re-jumelle la machine pour passer par le VPS.")
	}

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

	// Image des serveurs téléchargée ou mise à jour en arrière-plan
	go refreshImage()

	// Événements Docker (démarrage, arrêt, santé) transmis à l'API en direct
	events := make(chan serverEvent, 256)
	go watchDockerEvents(events, stopChan)
	// Garde-fous : journal qui explose, plantages en boucle (lot 35c)
	go watchServers(config.Docker.DataPath, events, stopChan)

	for {
		select {
		case <-stopChan:
			log.Println("Agent arrêté")
			return
		default:
			err := runConnection(config, stopChan, events)
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
