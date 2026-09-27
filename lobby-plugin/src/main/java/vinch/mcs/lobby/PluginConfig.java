package vinch.mcs.lobby;

import lombok.Data;
import org.bukkit.configuration.file.FileConfiguration;

@Data
public class PluginConfig {

    private String apiUrl;
    private String apiKey;

    public static PluginConfig from(FileConfiguration config) {
        PluginConfig pc = new PluginConfig();
        pc.setApiUrl(config.getString("api-url", "http://localhost:8081"));
        pc.setApiKey(config.getString("api-key", "CHANGE_ME"));
        return pc;
    }
}