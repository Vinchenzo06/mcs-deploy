package main

// Version de Java des serveurs (lot 35).
//
// Chaque version de Minecraft (et de Forge) demande sa version de Java : l'API
// choisit (automatiquement ou selon le propriétaire) et l'agent prend l'image
// itzg correspondante (java8, java11, java16, java17, java21, java25).
//
// Quand un serveur ne démarre pas, l'agent lit la fin de son journal et
// reconnaît les erreurs typiques d'une mauvaise version de Java. Le serveur est
// alors arrêté (sinon il redémarrerait en boucle) et l'API propose au joueur de
// choisir une autre version.

import (
	"bufio"
	"errors"
	"fmt"
	"log"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
)

var javaVersions = []int{8, 11, 16, 17, 21, 25}

// imageFor : image itzg de cette version de Java (0 ou inconnue = la plus récente)
func imageFor(java int) string {
	for _, v := range javaVersions {
		if v == java {
			return fmt.Sprintf("%s:java%d", serverImage, java)
		}
	}
	return serverImage
}

// javaError : le serveur ne démarre pas à cause de sa version de Java
type javaError struct {
	Needed    int    // version demandée par le journal (0 = inconnue)
	Direction string // "newer", "older" ou "" (l'API compare avec la version actuelle)
	Detail    string // ligne du journal qui l'a révélé
}

func (e *javaError) Error() string {
	switch e.Direction {
	case "newer":
		if e.Needed > 0 {
			return fmt.Sprintf("problème de version de Java : ce serveur demande Java %d ou plus récent", e.Needed)
		}
		return "problème de version de Java : ce serveur demande un Java plus récent"
	case "older":
		if e.Needed > 0 {
			return fmt.Sprintf("problème de version de Java : ce serveur demande Java %d au maximum", e.Needed)
		}
		return "problème de version de Java : ce serveur demande un Java plus ancien"
	}
	if e.Needed > 0 {
		return fmt.Sprintf("problème de version de Java : ce serveur demande Java %d", e.Needed)
	}
	return "problème de version de Java"
}

var (
	// "...compiled by a more recent version of the Java Runtime (class file version 65.0)..."
	reClassFile = regexp.MustCompile(`class file version (\d+)(?:\.\d+)?`)
	// Forge / ASM trop anciens pour le Java utilisé
	reMajor = regexp.MustCompile(`Unsupported class file major version (\d+)`)
	// Mixin : "The requested compatibility level JAVA_17 could not be set"
	reCompat = regexp.MustCompile(`compatibility level JAVA_(\d+)`)
	// "Unsupported Java detected (21.0). Only up to Java 17 is supported."
	reUpTo = regexp.MustCompile(`(?i)only up to java (\d+)`)
	// "requires Java 21", "requires at least Java 17", "Java 17 or newer is required"
	reRequires = regexp.MustCompile(`(?i)requires? (?:at least )?java (\d+)`)
	reOrNewer  = regexp.MustCompile(`(?i)java (\d+)\+? (?:or (?:newer|higher|above|later) )?is required`)
)

// diagnoseJavaLine reconnaît une erreur de version de Java dans une ligne de journal
func diagnoseJavaLine(line string) *javaError {
	detail := strings.TrimSpace(line)
	if len(detail) > 300 {
		detail = detail[:300]
	}
	num := func(m []string) int {
		n, _ := strconv.Atoi(m[1])
		return n
	}
	if strings.Contains(line, "UnsupportedClassVersionError") || strings.Contains(line, "compiled by a more recent version") {
		if m := reClassFile.FindStringSubmatch(line); m != nil && num(m) > 44 {
			return &javaError{Needed: num(m) - 44, Direction: "newer", Detail: detail}
		}
		return &javaError{Direction: "newer", Detail: detail}
	}
	if strings.Contains(line, "cannot be cast to") && strings.Contains(line, "URLClassLoader") {
		// Forge 1.16 et avant sur un Java 9+ : il lui faut Java 8
		return &javaError{Needed: 8, Direction: "older", Detail: detail}
	}
	if m := reMajor.FindStringSubmatch(line); m != nil {
		return &javaError{Direction: "older", Detail: detail}
	}
	if m := reUpTo.FindStringSubmatch(line); m != nil {
		return &javaError{Needed: num(m), Direction: "older", Detail: detail}
	}
	if m := reCompat.FindStringSubmatch(line); m != nil && strings.Contains(line, "could not be set") {
		return &javaError{Needed: num(m), Detail: detail}
	}
	if m := reOrNewer.FindStringSubmatch(line); m != nil {
		return &javaError{Needed: num(m), Direction: "newer", Detail: detail}
	}
	if m := reRequires.FindStringSubmatch(line); m != nil && strings.Contains(strings.ToLower(line), "java") {
		return &javaError{Needed: num(m), Direction: "newer", Detail: detail}
	}
	return nil
}

// diagnoseJava lit la fin du journal du conteneur à la recherche d'un problème de Java
func diagnoseJava(container string) *javaError {
	out, err := exec.Command("docker", "logs", "--tail", "400", container).CombinedOutput()
	if err != nil && len(out) == 0 {
		return nil
	}
	scanner := bufio.NewScanner(strings.NewReader(string(out)))
	scanner.Buffer(make([]byte, 0, 1024*1024), 1024*1024)
	for scanner.Scan() {
		if je := diagnoseJavaLine(scanner.Text()); je != nil {
			return je
		}
	}
	return nil
}

// javaFailure : après un démarrage raté, remplace l'erreur par un problème de Java
// s'il y en a un, et arrête le conteneur (sinon il redémarrerait en boucle)
func javaFailure(container string, err error) error {
	var je *javaError
	if !errors.As(err, &je) {
		je = diagnoseJava(container)
	}
	if je == nil {
		return err
	}
	log.Printf("Serveur %s : %v (%s)", container, je, je.Detail)
	_ = exec.Command("docker", "stop", "-t", "10", container).Run()
	return je
}

// containerCrashed : le conteneur s'est arrêté ou a déjà redémarré
func containerCrashed(container string) bool {
	out, err := exec.Command("docker", "inspect", "-f", "{{.State.Status}} {{.RestartCount}}", container).Output()
	if err != nil {
		return false
	}
	f := strings.Fields(string(out))
	return len(f) == 2 && (f[0] == "exited" || f[0] == "dead" || f[1] != "0")
}
