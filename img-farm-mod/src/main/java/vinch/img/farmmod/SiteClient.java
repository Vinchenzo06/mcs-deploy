package vinch.img.farmmod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Appels au site (img.asearia.ca) : /mod/link sans clé, le reste avec la clé du mod.
 * Les réponses arrivent hors du fil du jeu : l'appelant repasse par Minecraft.execute().
 */
final class SiteClient {

    record Reply(int status, JsonObject body, String error) {
        boolean ok() {
            return error == null && status >= 200 && status < 300;
        }

        String text(String key, String def) {
            JsonElement e = body == null ? null : body.get(key);
            return e == null || e.isJsonNull() ? def : e.getAsString();
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ModConfig config;
    private final String userAgent;

    SiteClient(ModConfig config, String modVersion) {
        this.config = config;
        this.userAgent = "img-farm-mod/" + modVersion;
    }

    CompletableFuture<Reply> post(String path, JsonObject body, boolean auth) {
        return send("POST", path, body, auth);
    }

    CompletableFuture<Reply> get(String path) {
        return send("GET", path, null, true);
    }

    CompletableFuture<Reply> delete(String path) {
        return send("DELETE", path, null, true);
    }

    private CompletableFuture<Reply> send(String method, String path, JsonObject body, boolean auth) {
        if (auth && (config.token == null || config.token.isBlank())) {
            return CompletableFuture.completedFuture(new Reply(401, null,
                    "Le mod n'est pas lié au site : prends un code dans Mon profil → Mod Fabric, puis /imgfarm lier CODE."));
        }
        HttpRequest.Builder b;
        try {
            b = HttpRequest.newBuilder(URI.create(config.site + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json");
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(new Reply(0, null, "Adresse du site invalide dans config/img-farm.json"));
        }
        if (auth) b.header("Authorization", "Bearer " + config.token);
        if (body != null) {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(SiteClient::parse)
                .exceptionally(e -> new Reply(0, null, "Le site ne répond pas (" + rootMessage(e) + "). Réessaie dans un moment."));
    }

    private static Reply parse(HttpResponse<String> res) {
        JsonObject json = null;
        try {
            JsonElement e = JsonParser.parseString(res.body());
            if (e.isJsonObject()) json = e.getAsJsonObject();
        } catch (Exception ignored) {
            // pas du JSON (page d'erreur de Caddy…)
        }
        int st = res.statusCode();
        if (st >= 200 && st < 300) return new Reply(st, json == null ? new JsonObject() : json, null);
        String err = json != null && json.has("error") && !json.get("error").isJsonNull()
                ? json.get("error").getAsString()
                : "Erreur du site (HTTP " + st + ")";
        return new Reply(st, json, err);
    }

    private static String rootMessage(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
