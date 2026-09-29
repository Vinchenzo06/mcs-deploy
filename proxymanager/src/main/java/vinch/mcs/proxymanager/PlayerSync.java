package vinch.mcs.proxymanager;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Envoie à l'API les quotas, le rôle admin et le rang réseau d'un joueur, lus
 * dans le LuckPerms du proxy. À la connexion, puis avant /mcs create et /mcs quota.
 * Sans LuckPerms sur le proxy, ne fait rien (le lobby synchronise aussi).
 */
public class PlayerSync {

    private final ApiClient apiClient;
    private final Logger logger;
    private final RankSource source;

    public PlayerSync(ProxyServer server, ApiClient apiClient, Logger logger) {
        this.apiClient = apiClient;
        this.logger = logger;
        RankSource s = null;
        if (server.getPluginManager().isLoaded("luckperms")) {
            try {
                // Par réflexion : les classes LuckPerms ne sont chargées que si le plugin est là
                s = (RankSource) Class.forName("vinch.mcs.proxymanager.LuckPermsBridge")
                        .getDeclaredConstructor().newInstance();
                logger.info("LuckPerms détecté sur le proxy : quotas et rangs synchronisés");
            } catch (Throwable t) {
                logger.warn("LuckPerms présent mais inutilisable : {}", t.toString());
            }
        } else {
            logger.warn("LuckPerms absent du proxy : quotas et rangs synchronisés seulement au lobby");
        }
        this.source = s;
    }

    public boolean enabled() {
        return source != null;
    }

    /**
     * Groupe LuckPerms "host" (titre ʜᴏsᴛ) d'un joueur, puis resynchronisation s'il
     * est en ligne. Renvoie false si LuckPerms n'est pas sur le proxy.
     */
    public CompletableFuture<Boolean> setHostGroup(com.velocitypowered.api.proxy.ProxyServer server,
                                                   java.util.UUID uuid, boolean member) {
        if (source == null) {
            return CompletableFuture.completedFuture(false);
        }
        return source.setGroup(uuid, "host", member)
                .thenCompose(v -> server.getPlayer(uuid).map(this::sync)
                        .orElse(CompletableFuture.completedFuture(null)))
                .thenApply(v -> true);
    }

    /** Ne échoue jamais : en cas de problème, les dernières valeurs connues restent */
    public CompletableFuture<Void> sync(Player player) {
        if (source == null) {
            return CompletableFuture.completedFuture(null);
        }
        return source.load(player.getUniqueId())
                .thenCompose(m -> apiClient.updatePlayerLimits(player.getUniqueId(), m.maxServers(), m.totalRamMb(),
                        m.totalCpuCores(), m.admin(), m.rank(), m.prefixJson(), m.nameColor(),
                        m.backupIntervalHours(), m.backupKeepLast(), m.backupKeepWeekly()))
                .handle((r, error) -> {
                    if (error != null) {
                        logger.warn("Synchronisation LuckPerms de {} : {}", player.getUsername(), error.getMessage());
                    }
                    return (Void) null;
                });
    }
}
