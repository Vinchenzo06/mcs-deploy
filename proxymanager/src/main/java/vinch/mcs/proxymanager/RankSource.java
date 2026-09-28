package vinch.mcs.proxymanager;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Quotas et rang réseau d'un joueur (LuckPerms du proxy, si installé) */
public interface RankSource {

    CompletableFuture<Meta> load(UUID uuid);

    /**
     * prefixJson : préfixe du groupe en composant texte JSON ("" si aucun) ;
     * nameColor : méta "name-color" (couleur du pseudo, ex. "green"), "" si aucune
     */
    record Meta(int maxServers, int totalRamMb, int totalCpuCores, boolean admin, String rank, String prefixJson,
                String nameColor) {}
}
