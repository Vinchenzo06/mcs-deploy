package main

// Lot 73 : tests de l'agent (go test ./... dans l'action « Agent ») : jamais de fichier hors
// du dossier du serveur, archives zip piégées refusées, mêmes calculs que l'API.

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCleanRel(t *testing.T) {
	ok := map[string]string{
		"server.properties":      "server.properties",
		"/plugins/x.jar":         "plugins/x.jar",
		`world\region\r.0.0.mca`: "world/region/r.0.0.mca",
		"  config/a.toml  ":      "config/a.toml",
	}
	for in, want := range ok {
		got, err := cleanRel(in, false)
		if err != nil || got != want {
			t.Errorf("cleanRel(%q) = %q, %v ; attendu %q", in, got, err, want)
		}
	}
	for _, bad := range []string{"", "/", "../etc/passwd", "a/../../b", "world/./x", ".ssh/id", "a//b", "plugins/.hidden", "a\x00b", strings.Repeat("a", 256)} {
		if got, err := cleanRel(bad, false); err == nil {
			t.Errorf("cleanRel(%q) aurait dû être refusé, a donné %q", bad, got)
		}
	}
	if got, err := cleanRel("", true); err != nil || got != "." {
		t.Errorf("racine permise : %q %v", got, err)
	}
}

func serverRoot(t *testing.T) (string, *os.Root) {
	t.Helper()
	dir := t.TempDir()
	root, err := os.OpenRoot(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { root.Close() })
	return dir, root
}

func TestFilesStayInsideServer(t *testing.T) {
	dir, root := serverRoot(t)
	outside := filepath.Join(t.TempDir(), "secret.txt")
	os.WriteFile(outside, []byte("secret"), 0644)
	os.WriteFile(filepath.Join(dir, "server.properties"), []byte("motd=x"), 0644)
	// Un lien posé dans le dossier du serveur (par un plugin, une archive…) ne mène jamais dehors
	if err := os.Symlink(outside, filepath.Join(dir, "lien")); err != nil {
		t.Fatal(err)
	}
	if _, err := filesOp(root, dir, "read", map[string]interface{}{"path": "lien", "offset": float64(0), "length": float64(100)}); err == nil {
		t.Fatal("lecture à travers un lien vers l'extérieur acceptée")
	}
	if _, err := filesOp(root, dir, "read", map[string]interface{}{"path": "../secret.txt"}); err == nil {
		t.Fatal("chemin .. accepté")
	}
	if _, err := filesOp(root, dir, "delete", map[string]interface{}{"path": "/"}); err == nil {
		t.Fatal("suppression du dossier du serveur lui-même acceptée")
	}
	res, err := filesOp(root, dir, "list", map[string]interface{}{"path": ""})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(strings.ToLower(toString(res)), "server.properties") {
		t.Errorf("liste sans server.properties : %v", res)
	}
	if b, _ := os.ReadFile(outside); string(b) != "secret" {
		t.Fatal("fichier extérieur modifié")
	}
}

func toString(v interface{}) string {
	var b strings.Builder
	switch x := v.(type) {
	case map[string]interface{}:
		for k, e := range x {
			b.WriteString(k + "=" + toString(e) + " ")
		}
	case []interface{}:
		for _, e := range x {
			b.WriteString(toString(e) + " ")
		}
	case []map[string]interface{}:
		for _, e := range x {
			b.WriteString(toString(e) + " ")
		}
	case string:
		b.WriteString(x)
	}
	return b.String()
}

func TestExtractRefusesEscapingEntries(t *testing.T) {
	dir, root := serverRoot(t)
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, content := range map[string]string{
		"world/level.dat":   "niveau",
		"../evil.txt":       "dehors",
		"a/../../evil2.txt": "dehors",
		".cache/x":          "caché",
	} {
		w, _ := zw.Create(name)
		w.Write([]byte(content))
	}
	zw.Close()
	os.MkdirAll(filepath.Join(dir, "uploads"), 0755)
	os.WriteFile(filepath.Join(dir, "uploads", "pack.zip"), buf.Bytes(), 0644)
	res, err := filesOp(root, dir, "extract", map[string]interface{}{"path": "uploads/pack.zip"})
	if err != nil {
		t.Fatal(err)
	}
	if res["files"] != 1 || res["skipped"] != 3 {
		t.Errorf("extraction : %v (attendu 1 fichier, 3 ignorés)", res)
	}
	if b, err := os.ReadFile(filepath.Join(dir, "uploads", "world", "level.dat")); err != nil || string(b) != "niveau" {
		t.Errorf("fichier normal non extrait : %v", err)
	}
	for _, p := range []string{filepath.Join(dir, "evil.txt"), filepath.Join(filepath.Dir(dir), "evil.txt"), filepath.Join(filepath.Dir(dir), "evil2.txt")} {
		if _, err := os.Stat(p); err == nil {
			t.Errorf("%s créé hors du dossier", p)
		}
	}
}

// Même calcul que l'API (ServerService.javaHeapMb, testé aussi de son côté)
func TestJavaHeapMatchesAPI(t *testing.T) {
	for ram, want := range map[int]int{600: 256, 1024: 512, 2048: 1536, 4096: 3277, 8192: 6554, 20000: 16928} {
		if got := javaHeapMb(ram); got != want {
			t.Errorf("javaHeapMb(%d) = %d, attendu %d", ram, got, want)
		}
	}
}
