package vinch.mcs.api.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vinch.mcs.api.entities.Server;
import vinch.mcs.api.entities.ServerType;

import java.util.Map;

/**
 * Forwarding du proxy, serveur par serveur (lot 36). Velocity est en mode "none" et
 * proxymanager applique le mode de chaque serveur :
 *  - legacy (BungeeCord) : Paper/Spigot (spigot.yml bungeecord: true), Forge 1.7–1.12
 *    (mod Proxy-Compatible-Forge en mode LEGACY) ;
 *  - modern (secret Velocity) : Fabric 1.16.5+ (FabricProxy-Lite), Forge 1.13+ et
 *    NeoForge (Proxy-Compatible-Forge) ;
 *  - none : le serveur ne voit pas le vrai compte des joueurs (Fabric avant 1.16.5,
 *    anciens serveurs Vanilla).
 * L'agent installe les mods (Modrinth, via l'image itzg) et écrit leur config.
 */
@Component
public class ProxyForwarding {

    private static volatile String secret = "";

    @Value("${mcs.velocity.forwarding-secret:}")
    public void setSecret(String value) {
        secret = value == null ? "" : value.trim();
    }

    public static String modeFor(Server s) {
        return modeFor(s.getServerType(), s.getMinecraftVersion());
    }

    public static String modeFor(ServerType type, String minecraftVersion) {
        int[] v = JavaVersions.parse(minecraftVersion);
        boolean latest = v == null || v[0] != 1; // LATEST, 26.x
        int minor = latest ? 99 : v[1];
        int patch = latest ? 0 : v[2];
        ServerType t = type == null ? ServerType.PAPER : type;
        return switch (t) {
            case PAPER, SPIGOT -> "legacy";
            case FABRIC -> minor > 16 || (minor == 16 && patch >= 5) ? "modern" : "none";
            case FORGE -> minor >= 13 ? "modern" : minor >= 7 ? "legacy" : "none";
            case NEOFORGE -> "modern";
            default -> "none";
        };
    }

    /** Ajoute le mode (et le secret s'il en faut un) aux données d'une commande d'agent */
    public static void apply(Map<String, Object> data, Server s) {
        String mode = modeFor(s);
        data.put("forwarding", mode);
        if ("modern".equals(mode) || "legacy".equals(mode) && s.getServerType() == ServerType.FORGE) {
            data.put("forwarding_secret", secret);
        }
    }
}
