//go:build go1.25

package main

import "os"

// Go 1.25 et plus (l'action GitHub compile avec la version de go.mod) : renommage
// et suppression récursive faits par os.Root lui-même, sans jamais sortir du dossier.

func renameInRoot(root *os.Root, from, to string) error {
	return root.Rename(from, to)
}

func removeAllInRoot(root *os.Root, name string) error {
	return root.RemoveAll(name)
}
