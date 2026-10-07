package main

// Image Docker des serveurs Minecraft.
//
// Sur une machine neuve, l'image n'est pas encore présente : « docker run » la
// téléchargeait en silence pendant la création (plusieurs minutes), si bien que
// l'API abandonnait avant la fin. L'agent la télécharge maintenant au démarrage
// (et la met à jour au passage), et une création qui la trouve absente la
// télécharge d'abord en l'annonçant dans le journal.

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

func imagePresent() bool {
	return exec.Command("docker", "image", "inspect", serverImage).Run() == nil
}

// pullImage télécharge (ou met à jour) l'image ; un seul téléchargement à la fois
func pullImage(reason string) error {
	imageMu.Lock()
	defer imageMu.Unlock()
	start := time.Now()
	log.Printf("Image %s : téléchargement (%s)...", serverImage, reason)
	out, err := exec.Command("docker", "pull", "-q", serverImage).CombinedOutput()
	if err != nil {
		return fmt.Errorf("téléchargement de l'image %s impossible : %s", serverImage,
			strings.TrimSpace(string(out)))
	}
	log.Printf("Image %s prête (%s)", serverImage, time.Since(start).Round(time.Second))
	return nil
}

// ensureImage : avant une création, l'image doit être là
func ensureImage() error {
	if imagePresent() {
		return nil
	}
	imageMu.Lock()
	present := imagePresent() // téléchargée entre-temps par refreshImage ?
	imageMu.Unlock()
	if present {
		return nil
	}
	return pullImage("première création sur cette machine")
}

// refreshImage : au démarrage de l'agent, en arrière-plan (nouvelles versions de
// Java et de Minecraft prises en charge par l'image)
func refreshImage() {
	reason := "mise à jour"
	if !imagePresent() {
		reason = "absente de cette machine"
	}
	if err := pullImage(reason); err != nil {
		log.Printf("ATTENTION : %v (nouvel essai à la prochaine création)", err)
	}
}
