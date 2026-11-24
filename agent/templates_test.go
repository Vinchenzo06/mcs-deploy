package main

// Lot 76 : fichiers du modèle d'un serveur à la demande

import (
	"encoding/base64"
	"os"
	"path/filepath"
	"testing"
)

func TestTemplateFiles(t *testing.T) {
	dir := t.TempDir()
	b := func(s string) string { return base64.StdEncoding.EncodeToString([]byte(s)) }
	os.MkdirAll(filepath.Join(dir, "plugins"), 0755)
	os.WriteFile(filepath.Join(dir, "plugins", "garde.yml"), []byte("à moi"), 0644)
	err := writeTemplateFiles(dir, []templateFile{
		{Path: "plugins/WorldReset/config.yml", Data: b("timer:\n  enabled: true\n")},
		{Path: "plugins/garde.yml", Data: b("écrasé ?")},
	})
	if err != nil {
		t.Fatal(err)
	}
	if got, _ := os.ReadFile(filepath.Join(dir, "plugins", "WorldReset", "config.yml")); string(got) != "timer:\n  enabled: true\n" {
		t.Errorf("config : %q", got)
	}
	if got, _ := os.ReadFile(filepath.Join(dir, "plugins", "garde.yml")); string(got) != "à moi" {
		t.Error("un fichier existant a été écrasé")
	}
	// « /etc/x » reste dans le dossier du serveur (le / du début est retiré)
	for _, bad := range []string{"../dehors.yml", "plugins/../../x", ".hidden/x"} {
		if err := writeTemplateFiles(dir, []templateFile{{Path: bad, Data: b("x")}}); err == nil {
			t.Errorf("%s accepté", bad)
		}
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(dir), "dehors.yml")); err == nil {
		t.Error("fichier créé hors du dossier")
	}
}
