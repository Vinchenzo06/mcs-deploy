package vinch.mcs.api.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vinch.mcs.api.services.ServerFilesService;

import java.util.Map;

/** Console (journal) et fichiers d'un serveur (lot 54) ; utilisé par le site IMG via le proxy */
@RestController
@RequestMapping("/api/v1/servers/{id}")
@RequiredArgsConstructor
public class ServerFilesController {

    private final ServerFilesService files;

    public record PathBody(Long playerId, String path) {}

    public record RenameBody(Long playerId, String from, String to) {}

    public record ArchiveBody(Long playerId, String dir, java.util.List<String> paths, String name) {}

    @FunctionalInterface
    private interface Action {
        Object run() throws Exception;
    }

    private static ResponseEntity<?> handle(Action action) {
        try {
            Object result = action.run();
            return ResponseEntity.ok(result == null ? Map.of("success", true) : result);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    @GetMapping("/files")
    public ResponseEntity<?> list(@PathVariable Long id, @RequestParam Long playerId,
                                  @RequestParam(defaultValue = "") String path) {
        return handle(() -> files.list(id, playerId, path));
    }

    /** Contenu brut ; en-têtes X-File-Size (taille totale) et X-File-Mtime */
    @GetMapping("/files/content")
    public ResponseEntity<?> read(@PathVariable Long id, @RequestParam Long playerId, @RequestParam String path,
                                  @RequestParam(defaultValue = "0") long offset,
                                  @RequestParam(defaultValue = "0") int length) {
        try {
            ServerFilesService.Content c = files.read(id, playerId, path, offset, length);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("X-File-Size", Long.toString(c.size()))
                    .header("X-File-Mtime", Long.toString(c.mtime()))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(c.data());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("error", msg));
        }
    }

    /** Corps brut (8 Mo au plus) ; final=true : le fichier remplace sa cible ; abort=true : abandon */
    @PostMapping("/files/content")
    public ResponseEntity<?> write(@PathVariable Long id, @RequestParam Long playerId, @RequestParam String path,
                                   @RequestParam(defaultValue = "0") long offset,
                                   @RequestParam(name = "final", defaultValue = "false") boolean fin,
                                   @RequestParam(defaultValue = "false") boolean abort,
                                   @RequestBody(required = false) byte[] body) {
        return handle(() -> Map.of("size", files.write(id, playerId, path, offset, body, fin, abort)));
    }

    @PostMapping("/files/mkdir")
    public ResponseEntity<?> mkdir(@PathVariable Long id, @RequestBody PathBody body) {
        return handle(() -> files.mkdir(id, body.playerId(), body.path()));
    }

    @PostMapping("/files/rename")
    public ResponseEntity<?> rename(@PathVariable Long id, @RequestBody RenameBody body) {
        return handle(() -> files.rename(id, body.playerId(), body.from(), body.to()));
    }

    @PostMapping("/files/delete")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestBody PathBody body) {
        return handle(() -> files.delete(id, body.playerId(), body.path()));
    }

    @PostMapping("/files/archive")
    public ResponseEntity<?> archive(@PathVariable Long id, @RequestBody ArchiveBody body) {
        return handle(() -> files.archive(id, body.playerId(), body.dir(), body.paths(), body.name()));
    }

    @PostMapping("/files/extract")
    public ResponseEntity<?> extract(@PathVariable Long id, @RequestBody PathBody body) {
        return handle(() -> files.extract(id, body.playerId(), body.path()));
    }

    @GetMapping("/logs")
    public ResponseEntity<?> logs(@PathVariable Long id, @RequestParam Long playerId,
                                  @RequestParam(defaultValue = "game") String source,
                                  @RequestParam(defaultValue = "-1") long offset) {
        return handle(() -> files.logs(id, playerId, source, offset));
    }
}
