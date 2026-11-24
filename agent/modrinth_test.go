package main

// Lot 70 : projets Modrinth passés à l'image itzg (testé au lot 73)

import (
	"strings"
	"testing"
)

func TestModrinthArgs(t *testing.T) {
	list := []string{"luckperms", "bad slug!", "chunky"}
	req := CreateServerRequest{Type: "PAPER", Forwarding: "legacy", ModrinthProjects: &list}
	got := strings.Join(forwardingArgs(req), " ")
	if !strings.Contains(got, "mcs.modrinth=luckperms,chunky") || !strings.Contains(got, "MODRINTH_PROJECTS=luckperms?,chunky?") {
		t.Fatal(got)
	}
	fab := []string{"lithium"}
	req = CreateServerRequest{Type: "FABRIC", Version: "1.21.4", Forwarding: "modern", ModrinthProjects: &fab}
	got = strings.Join(forwardingArgs(req), " ")
	if !strings.Contains(got, "MODRINTH_PROJECTS=fabricproxy-lite,lithium?") {
		t.Fatal(got)
	}
	empty := []string{}
	req = CreateServerRequest{Type: "PAPER", ModrinthProjects: &empty}
	got = strings.Join(forwardingArgs(req), " ")
	if strings.Contains(got, "MODRINTH_PROJECTS") || !strings.Contains(got, "mcs.modrinth=") {
		t.Fatal(got)
	}
	req.clearModrinth = true
	got = strings.Join(forwardingArgs(req), " ")
	if !strings.Contains(got, "MODRINTH_PROJECTS= ") {
		t.Fatal(got)
	}
	req = CreateServerRequest{Type: "PAPER"}
	got = strings.Join(forwardingArgs(req), " ")
	if strings.Contains(got, "modrinth") || strings.Contains(got, "MODRINTH") {
		t.Fatal("ancienne API : " + got)
	}
	v := []string{"x1"}
	req = CreateServerRequest{Type: "VANILLA", ModrinthProjects: &v}
	if strings.Contains(strings.Join(forwardingArgs(req), " "), "MODRINTH_PROJECTS") {
		t.Fatal("vanilla")
	}
}
