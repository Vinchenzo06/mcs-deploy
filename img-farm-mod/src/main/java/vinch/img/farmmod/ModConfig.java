package vinch.img.farmmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * config/img-farm.json : l'adresse du site, la clé du mod (secrète : ne la partage pas) et
 * l'affichage. La session en cours est à part : config/img-farm-session.json.
 */
final class ModConfig {

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    String site = "https://img.asearia.ca";
    String token;                      // clé reçue par /imgfarm lier ; null = pas lié
    String username;                   // membre du site lié
    boolean hud = true;                // cadre de la session en haut à gauche

    static Path dir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    static ModConfig load() {
        ModConfig c = read(dir().resolve("img-farm.json"), ModConfig.class);
        if (c == null) c = new ModConfig();
        if (c.site == null || !(c.site.startsWith("https://") || c.site.startsWith("http://"))) c.site = "https://img.asearia.ca";
        c.site = c.site.replaceAll("/+$", "");
        return c;
    }

    void save() {
        write(dir().resolve("img-farm.json"), this);
    }

    static FarmSession loadSession() {
        return read(dir().resolve("img-farm-session.json"), FarmSession.class);
    }

    static void saveSession(FarmSession s) {
        Path p = dir().resolve("img-farm-session.json");
        if (s == null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                ImgFarmMod.LOG.warn("Impossible de supprimer {} : {}", p, e.toString());
            }
            return;
        }
        write(p, s);
    }

    static <T> T read(Path p, Class<T> type) {
        if (!Files.isRegularFile(p)) return null;
        try {
            return GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            ImgFarmMod.LOG.warn("Fichier illisible {} : {}", p, e.toString());
            return null;
        }
    }

    /** Écrit à côté puis remplace : un plantage au milieu ne laisse jamais un fichier à moitié écrit */
    static void write(Path p, Object value) {
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(value), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            ImgFarmMod.LOG.warn("Impossible d'écrire {} : {}", p, e.toString());
        }
    }
}
