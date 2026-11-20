package vinch.mcs.proxymanager;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Quotas et rang réseau d'un joueur (LuckPerms du proxy, si installé) */
public interface RankSource {

    CompletableFuture<Meta> load(UUID uuid);

    /** Ajoute ou retire un groupe LuckPerms (ex. "host") */
    CompletableFuture<Void> setGroup(UUID uuid, String group, boolean member);

    /**
     * prefixJson : préfixe du groupe en composant texte JSON ("" si aucun) ;
     * nameColor : méta "name-color" (couleur du pseudo, ex. "green"), "" si aucune
     */
    record Meta(int maxServers, int totalRamMb, int totalCpuCores, boolean admin, String rank, String prefixJson,
                String nameColor, Integer backupIntervalHours, Integer backupKeepLast, Integer backupKeepWeekly) {}

    /**
     * Réglages d'un joueur modifiables depuis le site IMG Farm DB (lot 50) :
     * ownMaxServers = méta « max-servers » posée sur le joueur lui-même (null = celle de son groupe),
     * maxServers = valeur effective, admin = permission mcs.admin effective,
     * adminGroup = le joueur est lui-même dans le groupe « admin ».
     */
    record Settings(Integer ownMaxServers, int maxServers, boolean admin, boolean adminGroup) {}

    CompletableFuture<Settings> settings(UUID uuid);

    /** null = retire la valeur propre au joueur (il reprend celle de son groupe) */
    CompletableFuture<Void> setMaxServers(UUID uuid, Integer maxServers);
}
