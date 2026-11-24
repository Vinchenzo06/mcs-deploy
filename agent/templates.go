package main

// Lot 76 : fichiers du modèle d'un serveur à la demande (config de WorldReset…), posés par
// l'API à la création, avant le tout premier démarrage. Toujours dans le dossier du serveur
// (os.Root, chemins vérifiés par cleanRel) ; jamais par-dessus un fichier existant.

import (
	"encoding/base64"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path"
)

type templateFile struct {
	Path string `json:"path"`
	Data string `json:"data"` // base64
}

const maxTemplateBytes = 2 << 20

func writeTemplateFiles(serverPath string, files []templateFile) error {
	if len(files) == 0 {
		return nil
	}
	root, err := os.OpenRoot(serverPath)
	if err != nil {
		return err
	}
	defer root.Close()
	total := 0
	for _, f := range files {
		rel, err := cleanRel(f.Path, false)
		if err != nil {
			return fmt.Errorf("modèle : %s : %w", f.Path, err)
		}
		data, err := base64.StdEncoding.DecodeString(f.Data)
		if err != nil {
			return fmt.Errorf("modèle : %s illisible", rel)
		}
		total += len(data)
		if total > maxTemplateBytes {
			return errors.New("modèle trop gros")
		}
		if err := mkdirAllInRoot(root, path.Dir(rel)); err != nil {
			return err
		}
		if _, err := root.Lstat(rel); err == nil {
			continue // déjà là : jamais écrasé
		} else if !errors.Is(err, fs.ErrNotExist) {
			return err
		}
		out, err := root.OpenFile(rel, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0644)
		if err != nil {
			return err
		}
		_, werr := out.Write(data)
		_ = out.Chown(containerUID, containerGID)
		out.Close()
		if werr != nil {
			return werr
		}
	}
	return nil
}
