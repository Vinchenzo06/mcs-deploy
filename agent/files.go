package main

// Console et fichiers d'un serveur depuis le site IMG (lot 54).
//
// Tout passe par os.Root, ouvert sur le dossier du serveur : aucun chemin ni lien
// symbolique ne peut mener hors de ce dossier. Les noms qui commencent par un point
// sont cachés et refusés (fichiers internes de l'image, dont le mot de passe RCON,
// et les fichiers de MCS comme .mcs-team-plugins).
//
// Les gros fichiers voyagent par morceaux de 1 Mo au plus (dans les deux sens) :
// rien n'est jamais gardé en entier en mémoire, ni ici ni dans l'API.
// Un téléversement s'écrit dans un fichier caché « .<nom>.mcs-part », renommé à la fin.

import (
	"archive/zip"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"path"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode"

	"github.com/gorilla/websocket"
)

const (
	filesMaxChunk    = 1 << 20 // 1 Mo par message, dans les deux sens
	filesMaxEntries  = 5000
	filesDiskReserve = 1024 // Mo toujours laissés libres sur la machine
	logsMaxBytes     = 64 << 10
	logsDockerTail   = "300"
)

var ansiCodes = regexp.MustCompile(`\x1b\[[0-9;?]*[A-Za-z]`)

// cleanRel : chemin relatif au dossier du serveur, sans « .. » ni partie cachée.
// Vide (ou « / ») = le dossier du serveur lui-même, permis seulement si allowRoot.
func cleanRel(p string, allowRoot bool) (string, error) {
	p = strings.Trim(strings.ReplaceAll(strings.TrimSpace(p), "\\", "/"), "/")
	if p == "" {
		if allowRoot {
			return ".", nil
		}
		return "", errors.New("chemin vide")
	}
	if len(p) > 1024 {
		return "", errors.New("chemin trop long")
	}
	parts := strings.Split(p, "/")
	for _, c := range parts {
		if c == "" || c == "." || c == ".." {
			return "", errors.New("chemin invalide")
		}
		if strings.HasPrefix(c, ".") {
			return "", errors.New("les fichiers dont le nom commence par un point sont réservés")
		}
		if len(c) > 255 {
			return "", errors.New("nom trop long")
		}
		for _, r := range c {
			if unicode.IsControl(r) {
				return "", errors.New("caractère interdit dans le nom")
			}
		}
	}
	return strings.Join(parts, "/"), nil
}

// filesError : message lisible pour le site
func filesError(err error) string {
	switch {
	case errors.Is(err, fs.ErrNotExist):
		return "Fichier ou dossier introuvable (il a peut-être été déplacé ou supprimé)."
	case errors.Is(err, fs.ErrExist):
		return "Ce nom est déjà pris dans ce dossier."
	case errors.Is(err, fs.ErrPermission):
		return "Accès refusé par la machine."
	}
	msg := err.Error()
	if strings.Contains(msg, "escapes") || strings.Contains(msg, "path escapes") {
		return "Chemin refusé : il sort du dossier du serveur."
	}
	if strings.Contains(msg, "not a directory") {
		return "Ce chemin n'est pas un dossier."
	}
	if strings.Contains(msg, "is a directory") {
		return "C'est un dossier, pas un fichier."
	}
	if strings.Contains(msg, "directory not empty") {
		return "Le dossier n'est pas vide."
	}
	return msg
}

func openServerRoot(config *Config, msg map[string]interface{}) (*os.Root, map[string]interface{}, int64, error) {
	serverID, ok := getServerID(msg)
	if !ok {
		return nil, nil, 0, errors.New("server_id manquant")
	}
	data, _ := msg["data"].(map[string]interface{})
	dir := serverDataPath(config.Docker.DataPath, serverID)
	if st, err := os.Stat(dir); err != nil || !st.IsDir() {
		return nil, nil, 0, errors.New("les fichiers de ce serveur ne sont pas sur cette machine")
	}
	root, err := os.OpenRoot(dir)
	if err != nil {
		return nil, nil, 0, err
	}
	return root, data, serverID, nil
}

func dataString(data map[string]interface{}, key string) string {
	s, _ := data[key].(string)
	return s
}

func dataInt(data map[string]interface{}, key string) int64 {
	f, _ := data[key].(float64)
	return int64(f)
}

func dataBool(data map[string]interface{}, key string) bool {
	b, _ := data[key].(bool)
	return b
}

// handleFiles : une opération sur les fichiers d'un serveur (type files_<op>)
func handleFiles(conn *websocket.Conn, config *Config, commandId, op string, msg map[string]interface{}) {
	root, data, serverID, err := openServerRoot(config, msg)
	if err != nil {
		sendCommandError(conn, commandId, "files_failed", filesError(err))
		return
	}
	defer root.Close()
	result, err := filesOp(root, serverDataPath(config.Docker.DataPath, serverID), op, data)
	if err != nil {
		sendCommandError(conn, commandId, "files_failed", filesError(err))
		return
	}
	sendCommandResult(conn, commandId, result)
}

func filesOp(root *os.Root, dir, op string, data map[string]interface{}) (map[string]interface{}, error) {
	switch op {
	case "list":
		return filesList(root, dataString(data, "path"))
	case "read":
		return filesRead(root, dataString(data, "path"), dataInt(data, "offset"), dataInt(data, "length"))
	case "write":
		return filesWrite(root, dir, data)
	case "mkdir":
		p, err := cleanRel(dataString(data, "path"), false)
		if err != nil {
			return nil, err
		}
		if err := root.Mkdir(p, 0755); err != nil {
			return nil, err
		}
		if d, err := root.Open(p); err == nil {
			_ = d.Chown(containerUID, containerGID)
			d.Close()
		}
		return map[string]interface{}{"path": p}, nil
	case "rename":
		from, err := cleanRel(dataString(data, "from"), false)
		if err != nil {
			return nil, err
		}
		to, err := cleanRel(dataString(data, "to"), false)
		if err != nil {
			return nil, err
		}
		if _, err := root.Lstat(from); err != nil {
			return nil, err
		}
		if _, err := root.Lstat(to); err == nil {
			return nil, fs.ErrExist
		}
		if strings.HasPrefix(to+"/", from+"/") {
			return nil, errors.New("impossible de déplacer un dossier dans lui-même")
		}
		if err := renameInRoot(root, from, to); err != nil {
			return nil, err
		}
		return map[string]interface{}{"path": to}, nil
	case "archive":
		return filesArchive(root, dir, data)
	case "extract":
		return filesExtract(root, dir, data)
	case "delete":
		p, err := cleanRel(dataString(data, "path"), false)
		if err != nil {
			return nil, err
		}
		if _, err := root.Lstat(p); err != nil {
			return nil, err
		}
		if err := removeAllInRoot(root, p); err != nil {
			return nil, err
		}
		return map[string]interface{}{"path": p}, nil
	}
	return nil, fmt.Errorf("opération inconnue : %s", op)
}

func filesList(root *os.Root, rel string) (map[string]interface{}, error) {
	p, err := cleanRel(rel, true)
	if err != nil {
		return nil, err
	}
	d, err := root.Open(p)
	if err != nil {
		return nil, err
	}
	defer d.Close()
	if st, err := d.Stat(); err != nil {
		return nil, err
	} else if !st.IsDir() {
		return nil, errors.New("not a directory")
	}
	entries := []map[string]interface{}{}
	truncated := false
	for {
		batch, err := d.ReadDir(500)
		for _, e := range batch {
			if strings.HasPrefix(e.Name(), ".") {
				continue
			}
			if len(entries) >= filesMaxEntries {
				truncated = true
				break
			}
			item := map[string]interface{}{"name": e.Name(), "dir": e.IsDir()}
			if e.Type()&fs.ModeSymlink != 0 {
				item["link"] = true
			}
			if info, err := e.Info(); err == nil {
				if !e.IsDir() {
					item["size"] = info.Size()
				}
				item["mtime"] = info.ModTime().UnixMilli()
			}
			entries = append(entries, item)
		}
		if err == io.EOF || len(batch) == 0 || truncated {
			break
		}
		if err != nil {
			return nil, err
		}
	}
	sort.Slice(entries, func(i, j int) bool {
		di, dj := entries[i]["dir"].(bool), entries[j]["dir"].(bool)
		if di != dj {
			return di
		}
		return strings.ToLower(entries[i]["name"].(string)) < strings.ToLower(entries[j]["name"].(string))
	})
	return map[string]interface{}{"path": p, "entries": entries, "truncated": truncated}, nil
}

func filesRead(root *os.Root, rel string, offset, length int64) (map[string]interface{}, error) {
	p, err := cleanRel(rel, false)
	if err != nil {
		return nil, err
	}
	if offset < 0 {
		return nil, errors.New("position invalide")
	}
	if length <= 0 || length > filesMaxChunk {
		length = filesMaxChunk
	}
	f, err := root.Open(p)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if st.IsDir() {
		return nil, errors.New("is a directory")
	}
	if offset > st.Size() {
		offset = st.Size()
	}
	if rest := st.Size() - offset; rest < length {
		length = rest
	}
	buf := make([]byte, length)
	n, err := f.ReadAt(buf, offset)
	if err != nil && err != io.EOF {
		return nil, err
	}
	return map[string]interface{}{
		"data":  base64.StdEncoding.EncodeToString(buf[:n]),
		"size":  st.Size(),
		"mtime": st.ModTime().UnixMilli(),
	}, nil
}

// partName : fichier caché où s'écrit un téléversement avant d'être renommé
func partName(p string) string {
	dir, base := path.Split(p)
	return dir + "." + base + ".mcs-part"
}

// filesWrite : un morceau d'un téléversement. offset 0 = on recommence ; les morceaux
// suivants doivent arriver dans l'ordre. final = le fichier remplace sa cible ;
// abort = le téléversement est abandonné (le fichier caché est supprimé).
func filesWrite(root *os.Root, dir string, data map[string]interface{}) (map[string]interface{}, error) {
	p, err := cleanRel(dataString(data, "path"), false)
	if err != nil {
		return nil, err
	}
	part := partName(p)
	if dataBool(data, "abort") {
		_ = root.Remove(part)
		return map[string]interface{}{"aborted": true}, nil
	}
	chunk, err := base64.StdEncoding.DecodeString(dataString(data, "data"))
	if err != nil {
		return nil, errors.New("morceau illisible")
	}
	if len(chunk) > filesMaxChunk {
		return nil, errors.New("morceau trop gros")
	}
	if _, free := diskInfo(dir); free > 0 && free-int64(len(chunk)>>20) < filesDiskReserve {
		return nil, errors.New("le disque de la machine est presque plein : téléversement refusé")
	}
	if st, err := root.Lstat(p); err == nil && st.IsDir() {
		return nil, errors.New("un dossier porte déjà ce nom")
	}
	offset := dataInt(data, "offset")
	flags := os.O_WRONLY | os.O_CREATE
	if offset == 0 {
		flags |= os.O_TRUNC
	}
	f, err := root.OpenFile(part, flags, 0644)
	if err != nil {
		return nil, err
	}
	st, err := f.Stat()
	if err != nil || st.Size() != offset {
		f.Close()
		return nil, errors.New("envoi interrompu : recommence le téléversement")
	}
	if _, err := f.WriteAt(chunk, offset); err != nil {
		f.Close()
		return nil, err
	}
	_ = f.Chown(containerUID, containerGID)
	if err := f.Close(); err != nil {
		return nil, err
	}
	size := offset + int64(len(chunk))
	if dataBool(data, "final") {
		if err := renameInRoot(root, part, p); err != nil {
			return nil, err
		}
	}
	return map[string]interface{}{"size": size}, nil
}

// handleServerLogs : la suite du journal du jeu (logs/latest.log) à partir d'une position,
// ou la fin du journal du conteneur (démarrage, plantages avant que le jeu écrive son journal).
func handleServerLogs(conn *websocket.Conn, config *Config, commandId string, msg map[string]interface{}) {
	serverID, ok := getServerID(msg)
	if !ok {
		sendCommandError(conn, commandId, "missing_server_id", "server_id manquant")
		return
	}
	data, _ := msg["data"].(map[string]interface{})
	if dataString(data, "source") == "docker" {
		out, err := dockerLogs(serverID)
		if err != nil {
			sendCommandError(conn, commandId, "logs_failed", err.Error())
			return
		}
		sendCommandResult(conn, commandId, map[string]interface{}{"text": out})
		return
	}
	root, err := os.OpenRoot(serverDataPath(config.Docker.DataPath, serverID))
	if err != nil {
		sendCommandError(conn, commandId, "logs_failed", "les fichiers de ce serveur ne sont pas sur cette machine")
		return
	}
	defer root.Close()
	result, err := readGameLog(root, dataInt(data, "offset"))
	if err != nil {
		sendCommandError(conn, commandId, "logs_failed", filesError(err))
		return
	}
	sendCommandResult(conn, commandId, result)
}

// readGameLog : offset < 0 = les dernières lignes ; offset plus grand que le fichier =
// le serveur a redémarré (nouveau journal), on repart du début.
func readGameLog(root *os.Root, offset int64) (map[string]interface{}, error) {
	f, err := root.Open("logs/latest.log")
	if errors.Is(err, fs.ErrNotExist) {
		return map[string]interface{}{"text": "", "offset": 0, "missing": true}, nil
	}
	if err != nil {
		return nil, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return nil, err
	}
	size := st.Size()
	reset := false
	if offset < 0 {
		offset = size - logsMaxBytes
		if offset < 0 {
			offset = 0
		}
		reset = true
	} else if offset > size {
		offset, reset = 0, true
	}
	n := size - offset
	if n > logsMaxBytes {
		n = logsMaxBytes
	}
	buf := make([]byte, n)
	read, err := f.ReadAt(buf, offset)
	if err != nil && err != io.EOF {
		return nil, err
	}
	buf = buf[:read]
	// On s'arrête à la fin de la dernière ligne complète : la ligne en cours d'écriture
	// arrivera au prochain appel (sauf une ligne géante qui remplirait tout le morceau)
	if i := strings.LastIndexByte(string(buf), '\n'); i >= 0 {
		buf = buf[:i+1]
	} else if int64(len(buf)) < logsMaxBytes {
		buf = buf[:0]
	}
	text := string(buf)
	if reset && offset > 0 {
		// Premier affichage au milieu du fichier : on commence à une ligne entière
		if i := strings.IndexByte(text, '\n'); i >= 0 {
			text = text[i+1:]
		}
	}
	return map[string]interface{}{
		"text":   strings.ToValidUTF8(ansiCodes.ReplaceAllString(text, ""), "?"),
		"offset": offset + int64(len(buf)),
		"size":   size,
		"reset":  reset,
	}, nil
}

func dockerLogs(serverID int64) (string, error) {
	cmd := exec.Command("docker", "logs", "--tail", logsDockerTail, containerName(serverID))
	done := make(chan struct{})
	var out []byte
	var err error
	go func() {
		out, err = cmd.CombinedOutput()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(15 * time.Second):
		_ = cmd.Process.Kill()
		<-done
		return "", errors.New("pas de réponse de Docker (délai dépassé)")
	}
	text := strings.ToValidUTF8(ansiCodes.ReplaceAllString(string(out), ""), "?")
	if err != nil && strings.Contains(text, "No such container") {
		return "", errors.New("le conteneur de ce serveur n'existe pas sur cette machine")
	}
	if err != nil && text == "" {
		return "", err
	}
	if len(text) > logsMaxBytes {
		text = text[len(text)-logsMaxBytes:]
		if i := strings.IndexByte(text, '\n'); i >= 0 {
			text = text[i+1:]
		}
	}
	return text, nil
}

// ---------- Archives zip (lot 59) : compresser une sélection, extraire une archive ----------

// mkdirAllInRoot crée les dossiers d'un chemin (propriétaire : le conteneur), sans sortir du dossier
func mkdirAllInRoot(root *os.Root, p string) error {
	if p == "" || p == "." {
		return nil
	}
	cur := ""
	for _, part := range strings.Split(p, "/") {
		cur = path.Join(cur, part)
		if err := root.Mkdir(cur, 0755); err != nil && !errors.Is(err, fs.ErrExist) {
			return err
		}
		st, err := root.Lstat(cur)
		if err != nil {
			return err
		}
		if !st.IsDir() {
			return fmt.Errorf("%s existe déjà et n'est pas un dossier", cur)
		}
		if d, err := root.Open(cur); err == nil {
			_ = d.Chown(containerUID, containerGID)
			d.Close()
		}
	}
	return nil
}

// sizeInRoot : taille totale d'un fichier ou d'un dossier (liens symboliques et noms en « . » ignorés)
func sizeInRoot(root *os.Root, rel string) (int64, error) {
	st, err := root.Lstat(rel)
	if err != nil {
		return 0, err
	}
	if st.Mode().IsRegular() {
		return st.Size(), nil
	}
	if !st.IsDir() {
		return 0, nil
	}
	d, err := root.Open(rel)
	if err != nil {
		return 0, err
	}
	entries, err := d.ReadDir(-1)
	d.Close()
	if err != nil {
		return 0, err
	}
	var total int64
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), ".") {
			continue
		}
		n, err := sizeInRoot(root, path.Join(rel, e.Name()))
		if err != nil {
			return 0, err
		}
		total += n
	}
	return total, nil
}

func addToZip(zw *zip.Writer, root *os.Root, rel, base string) error {
	st, err := root.Lstat(rel)
	if err != nil {
		return err
	}
	name := strings.TrimPrefix(rel, base+"/")
	if base == "." {
		name = rel
	}
	if st.IsDir() {
		if _, err := zw.CreateHeader(&zip.FileHeader{Name: name + "/", Modified: st.ModTime()}); err != nil {
			return err
		}
		d, err := root.Open(rel)
		if err != nil {
			return err
		}
		entries, err := d.ReadDir(-1)
		d.Close()
		if err != nil {
			return err
		}
		for _, e := range entries {
			if strings.HasPrefix(e.Name(), ".") {
				continue
			}
			if err := addToZip(zw, root, path.Join(rel, e.Name()), base); err != nil {
				return err
			}
		}
		return nil
	}
	if !st.Mode().IsRegular() {
		return nil // lien symbolique ou autre : ignoré
	}
	h, err := zip.FileInfoHeader(st)
	if err != nil {
		return err
	}
	h.Name = name
	h.Method = zip.Deflate
	w, err := zw.CreateHeader(h)
	if err != nil {
		return err
	}
	f, err := root.Open(rel)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = io.Copy(w, f)
	return err
}

// filesArchive : zip des éléments choisis d'un dossier, écrit dans ce dossier (nom.zip)
func filesArchive(root *os.Root, dir string, data map[string]interface{}) (map[string]interface{}, error) {
	base, err := cleanRel(dataString(data, "dir"), true)
	if err != nil {
		return nil, err
	}
	name := strings.TrimSpace(dataString(data, "name"))
	if !strings.HasSuffix(strings.ToLower(name), ".zip") {
		name += ".zip"
	}
	if strings.Contains(name, "/") {
		return nil, errors.New("nom d'archive invalide")
	}
	target, err := cleanRel(path.Join(base, name), false)
	if err != nil {
		return nil, err
	}
	if _, err := root.Lstat(target); err == nil {
		return nil, fs.ErrExist
	}
	raw, _ := data["paths"].([]interface{})
	if len(raw) == 0 {
		return nil, errors.New("rien à compresser")
	}
	var items []string
	var total int64
	for _, v := range raw {
		s, _ := v.(string)
		p, err := cleanRel(path.Join(base, s), false)
		if err != nil {
			return nil, err
		}
		if base != "." && !strings.HasPrefix(p, base+"/") {
			return nil, errors.New("chemin invalide")
		}
		n, err := sizeInRoot(root, p)
		if err != nil {
			return nil, err
		}
		total += n
		items = append(items, p)
	}
	if _, free := diskInfo(dir); free > 0 && free-(total>>20) < filesDiskReserve {
		return nil, errors.New("le disque de la machine est presque plein : archive refusée")
	}
	part := partName(target)
	f, err := root.OpenFile(part, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0644)
	if err != nil {
		return nil, err
	}
	zw := zip.NewWriter(f)
	for _, p := range items {
		if err = addToZip(zw, root, p, base); err != nil {
			break
		}
	}
	if cerr := zw.Close(); err == nil {
		err = cerr
	}
	_ = f.Chown(containerUID, containerGID)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		_ = root.Remove(part)
		return nil, err
	}
	if err := renameInRoot(root, part, target); err != nil {
		_ = root.Remove(part)
		return nil, err
	}
	st, _ := root.Stat(target)
	size := int64(0)
	if st != nil {
		size = st.Size()
	}
	return map[string]interface{}{"path": target, "size": size, "source": total}, nil
}

// filesExtract : décompresse une archive zip dans son dossier. Les fichiers existants sont
// remplacés ; les chemins qui sortiraient du dossier, cachés (« . ») ou liens sont ignorés ;
// on s'arrête si le disque n'a plus de place (1 Go gardé libre).
func filesExtract(root *os.Root, dir string, data map[string]interface{}) (map[string]interface{}, error) {
	p, err := cleanRel(dataString(data, "path"), false)
	if err != nil {
		return nil, err
	}
	dest := path.Dir(p)
	f, err := root.Open(p)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return nil, err
	}
	zr, err := zip.NewReader(f, st.Size())
	if err != nil {
		return nil, errors.New("ce fichier n'est pas une archive zip lisible")
	}
	_, free := diskInfo(dir)
	budget := (free - filesDiskReserve) << 20
	if free <= 0 {
		budget = 1 << 62
	}
	files, skipped := 0, 0
	for _, e := range zr.File {
		target, err := cleanRel(path.Join(dest, e.Name), false)
		if err != nil || (dest != "." && !strings.HasPrefix(target, dest+"/")) || e.Mode()&fs.ModeSymlink != 0 {
			skipped++
			continue
		}
		if e.FileInfo().IsDir() {
			if err := mkdirAllInRoot(root, target); err != nil {
				return nil, err
			}
			continue
		}
		if err := mkdirAllInRoot(root, path.Dir(target)); err != nil {
			return nil, err
		}
		if lst, err := root.Lstat(target); err == nil && lst.IsDir() {
			return nil, fmt.Errorf("%s est un dossier : impossible de le remplacer par un fichier", target)
		}
		rc, err := e.Open()
		if err != nil {
			return nil, err
		}
		out, err := root.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0644)
		if err != nil {
			rc.Close()
			return nil, err
		}
		n, err := io.Copy(out, io.LimitReader(rc, budget+1))
		rc.Close()
		_ = out.Chown(containerUID, containerGID)
		out.Close()
		if err != nil {
			return nil, err
		}
		budget -= n
		if budget < 0 {
			_ = root.Remove(target)
			return nil, fmt.Errorf("le disque de la machine est presque plein : extraction arrêtée après %d fichier(s)", files)
		}
		files++
	}
	return map[string]interface{}{"path": dest, "files": files, "skipped": skipped}, nil
}
