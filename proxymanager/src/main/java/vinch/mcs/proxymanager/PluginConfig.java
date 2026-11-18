package vinch.mcs.proxymanager;

import lombok.Data;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Data
public class PluginConfig {

    private String apiUrl = "http://localhost:8081";
    private String apiKey = "CHANGE_ME";

    // Configuration de l'API locale exposée par ce plugin
    private int localApiPort = 8082;
    private String localApiKey = "CHANGE_ME_PLUGIN";

    // Masquage des IP des joueurs envoyées aux serveurs (voir IpMasker)
    private boolean maskPlayerIps = true;
    private String ipMaskKey = "";

    // Entrée IMG (lot 40) : adresse tapée par les membres et lobby IMG « pseudo/nom ».
    // Vides = désactivé.
    private String imgHost = "";
    private String imgLobby = "";
    // Liste des serveurs du jeu pour l'adresse IMG (lot 41) : icône (fichier ou URL)
    // et message (codes « & », \n = 2e ligne). Vides = ceux de Velocity.
    private String imgIcon = "";
    private String imgMotd = "";

    public static PluginConfig load(Path dataDirectory) throws IOException {
        Path configFile = dataDirectory.resolve("config.yml");

        if (!Files.exists(configFile)) {
            Files.createDirectories(dataDirectory);
            try (InputStream defaultConfig = PluginConfig.class.getResourceAsStream("/config.yml")) {
                if (defaultConfig != null) {
                    Files.copy(defaultConfig, configFile);
                }
            }
        }

        LoadSettings settings = LoadSettings.builder().build();
        Load yaml = new Load(settings);

        try (InputStream input = Files.newInputStream(configFile)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) yaml.loadFromInputStream(input);

            PluginConfig config = new PluginConfig();
            if (data != null) {
                if (data.containsKey("api-url")) config.setApiUrl((String) data.get("api-url"));
                if (data.containsKey("api-key")) config.setApiKey((String) data.get("api-key"));
                if (data.containsKey("local-api-port")) config.setLocalApiPort((Integer) data.get("local-api-port"));
                if (data.containsKey("local-api-key")) config.setLocalApiKey((String) data.get("local-api-key"));
                if (data.containsKey("mask-player-ips")) config.setMaskPlayerIps(Boolean.TRUE.equals(data.get("mask-player-ips")));
                if (data.containsKey("ip-mask-key")) config.setIpMaskKey(String.valueOf(data.get("ip-mask-key")));
                if (data.get("img-host") != null) config.setImgHost(String.valueOf(data.get("img-host")));
                if (data.get("img-lobby") != null) config.setImgLobby(String.valueOf(data.get("img-lobby")));
                if (data.get("img-icon") != null) config.setImgIcon(String.valueOf(data.get("img-icon")));
                if (data.get("img-motd") != null) config.setImgMotd(String.valueOf(data.get("img-motd")));
            }
            return config;
        }
    }
}