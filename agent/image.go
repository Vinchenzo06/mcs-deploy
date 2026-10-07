package main

// Images Docker des serveurs Minecraft (une par version de Java, lot 35).
//
// Sur une machine neuve, l'image n'est pas encore présente : « docker run » la
// téléchargeait en silence pendant la création (plusieurs minutes), si bien que
// l'API abandonnait avant la fin. L'agent met à jour au démarrage les images
// déjà présentes (ou télécharge la plus récente), et une création qui trouve
// son image absente la télécharge d'abord en l'annonçant dans le journal.

import (
	"fmt"
	"log"
	"os/exec"
	"strings"
	"sync"
	"time"
)

const serverImage = "itzg/minecraft-server"

var imageMu sync.Mutex

func imagePresent(image string) bool {
	return exec.Command("docker", "image", "inspect", image).Run() == nil
}

// pullImage télécharge (ou met à jour) une image ; un seul téléchargement à la fois
func pullImage(image, reason string) error {
	imageMu.Lock()
	defer imageMu.Unlock()
	start := time.Now()
	log.Printf("Image %s : téléchargement (%s)...", image, reason)
	out, err := exec.Command("docker", "pull", "-q", image).CombinedOutput()
	if err != nil {
		return fmt.Errorf("téléchargement de l'image %s impossible : %s", image, strings.TrimSpace(string(out)))
	}
	log.Printf("Image %s prête (%s)", image, time.Since(start).Round(time.Second))
	return nil
}

// ensureImage : avant une création, l'image doit être là
func ensureImage(image string) error {
	if imagePresent(image) {
		return nil
	}
	imageMu.Lock()
	present := imagePresent(image) // téléchargée entre-temps ?
	imageMu.Unlock()
	if present {
		return nil
	}
	return pullImage(image, "première utilisation sur cette machine")
}

// refreshImage : au démarrage de l'agent, en arrière-plan. Les images déjà
// présentes sont mises à jour (nouvelles versions de Minecraft prises en charge) ;
// sans aucune image, la plus récente est téléchargée.
func refreshImage() {
	out, _ := exec.Command("docker", "image", "ls", serverImage, "--format", "{{.Tag}}").Output()
	var tags []string
	for _, t := range strings.Fields(string(out)) {
		if t != "<none>" {
			tags = append(tags, t)
		}
	}
	if len(tags) == 0 {
		if err := pullImage(imageFor(25), "absente de cette machine"); err != nil {
			log.Printf("ATTENTION : %v (nouvel essai à la prochaine création)", err)
		}
		return
	}
	for _, t := range tags {
		if err := pullImage(serverImage+":"+t, "mise à jour"); err != nil {
			log.Printf("ATTENTION : %v", err)
		}
	}
}
