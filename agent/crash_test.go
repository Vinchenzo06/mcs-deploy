package main

import (
	"strings"
	"testing"
)

// Lot 76e : cause d'un arrêt juste après le démarrage
func TestCrashCause(t *testing.T) {
	logs := "[init] Running as uid=1000\n[init] Starting the Minecraft server...\n" +
		"[12:00:01 INFO]: Done (12.3s)! For help, type \"help\"\n" +
		"[12:00:03 INFO]: Player joined from /1.2.3.4:5555\n" +
		"[init] Running as uid=1000\n[init] Starting the Minecraft server...\n[12:01:00 INFO]: Loading"
	// Départ raté pendant la préparation (lignes [init] seulement)
	dl := "[init] Running as uid=1000\n[init] Resolving Modrinth projects\n[init] ERROR mc-image-helper : failed to download worldreset\n" +
		"[init] Running as uid=1000\n[12:01:00 INFO]: Done"
	if c, _ := crashCauseFrom("false 0", dl); !strings.Contains(c, "erreur de préparation") || !strings.Contains(c, "worldreset") {
		t.Errorf("préparation : %q", c)
	}
	cause, before := crashCauseFrom("false 0", logs)
	if !strings.HasPrefix(cause, "dernière ligne : ") || strings.Contains(cause, "1.2.3.4") || !strings.Contains(cause, "x.x.x.x") {
		t.Errorf("cause : %q", cause)
	}
	if len(before) != 4 { // les 4 lignes du départ précédent
		t.Errorf("lignes avant l'arrêt : %d", len(before))
	}
	if c, _ := crashCauseFrom("true 137", logs); !strings.Contains(c, "mémoire du conteneur") {
		t.Errorf("OOM : %q", c)
	}
	oom := "[init] x\nException in thread \"main\" java.lang.OutOfMemoryError: Java heap space\n[init] x"
	if c, _ := crashCauseFrom("false 1", oom); !strings.Contains(c, "OutOfMemoryError") {
		t.Errorf("OOM Java : %q", c)
	}
	if c, _ := crashCauseFrom("false 0", ""); c != "" {
		t.Errorf("vide : %q", c)
	}
}
