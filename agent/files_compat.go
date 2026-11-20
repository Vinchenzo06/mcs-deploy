//go:build !go1.25

package main

// Repli pour une compilation avec Go 1.24 (tests locaux) : os.Root n'a ni Rename ni
// RemoveAll. Le renommage copie le fichier (les dossiers ne se renomment pas ici) ;
// la suppression descend dans les dossiers sans suivre les liens symboliques.

import (
	"errors"
	"io"
	"io/fs"
	"os"
	"path"
)

func renameInRoot(root *os.Root, from, to string) error {
	st, err := root.Lstat(from)
	if err != nil {
		return err
	}
	if !st.Mode().IsRegular() {
		return errors.New("renommer un dossier demande un agent compilé avec Go 1.25 ou plus")
	}
	src, err := root.Open(from)
	if err != nil {
		return err
	}
	defer src.Close()
	dst, err := root.OpenFile(to, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0644)
	if err != nil {
		return err
	}
	if _, err := io.Copy(dst, src); err != nil {
		dst.Close()
		return err
	}
	_ = dst.Chown(containerUID, containerGID)
	if err := dst.Close(); err != nil {
		return err
	}
	return root.Remove(from)
}

func removeAllInRoot(root *os.Root, name string) error {
	st, err := root.Lstat(name)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if st.IsDir() {
		d, err := root.Open(name)
		if err != nil {
			return err
		}
		entries, err := d.ReadDir(-1)
		d.Close()
		if err != nil {
			return err
		}
		for _, e := range entries {
			if err := removeAllInRoot(root, path.Join(name, e.Name())); err != nil {
				return err
			}
		}
	}
	return root.Remove(name)
}
