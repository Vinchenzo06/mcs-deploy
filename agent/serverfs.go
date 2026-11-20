package main

import (
	"errors"
	"io"
	"io/fs"
	"os"
)

// Fichiers d'un serveur (lot 47) : son dossier appartient au conteneur, donc au joueur,
// alors que l'agent tourne en root. Toute lecture ou écriture de l'agent dans ce dossier
// passe par os.Root : un lien symbolique qui mènerait hors du dossier du serveur est refusé
// (sinon un serveur pourrait faire écrire l'agent dans /etc, par exemple).

const maxServerConfigBytes = 16 << 20

func readServerFile(serverPath, rel string) ([]byte, error) {
	root, err := os.OpenRoot(serverPath)
	if err != nil {
		return nil, err
	}
	defer root.Close()
	f, err := root.Open(rel)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return io.ReadAll(io.LimitReader(f, maxServerConfigBytes))
}

func statServerFile(serverPath, rel string) (fs.FileInfo, error) {
	root, err := os.OpenRoot(serverPath)
	if err != nil {
		return nil, err
	}
	defer root.Close()
	return root.Stat(rel)
}

// writeServerFile remplace le contenu d'un fichier du serveur (créé au besoin) ;
// propriétaire : l'utilisateur du conteneur. Les droits d'un fichier existant sont gardés.
func writeServerFile(serverPath, rel string, data []byte) error {
	root, err := os.OpenRoot(serverPath)
	if err != nil {
		return err
	}
	defer root.Close()
	return writeInRoot(root, rel, data)
}

// mkdirServer crée un sous-dossier du serveur (propriétaire : le conteneur) s'il n'existe pas
func mkdirServer(serverPath, rel string) error {
	root, err := os.OpenRoot(serverPath)
	if err != nil {
		return err
	}
	defer root.Close()
	if err := root.Mkdir(rel, 0755); err != nil && !errors.Is(err, fs.ErrExist) {
		return err
	}
	d, err := root.Open(rel)
	if err != nil {
		return err
	}
	defer d.Close()
	if st, err := d.Stat(); err != nil || !st.IsDir() {
		return errors.New(rel + " n'est pas un dossier")
	}
	_ = d.Chown(containerUID, containerGID)
	return nil
}
