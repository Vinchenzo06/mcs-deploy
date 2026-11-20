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

    /** Méta LuckPerms des quotas */
    String MAX_SERVERS = "max-servers";
    String TOTAL_RAM = "total-ram";
    String TOTAL_CPU = "total-cpu";

    /** Quotas posés à un niveau (joueur ou groupe) : null = pas réglé à ce niveau */
    record Quotas(Integer maxServers, Integer ramMb, Integer cpu) {}

    /**
     * Réglages d'un joueur pour le site IMG Farm DB (lots 50-51) : own = quotas posés sur le
     * joueur lui-même (passent par-dessus ses groupes) ; maxServers, ramMb, cpu = valeurs
     * effectives ; admin = permission mcs.admin effective ; adminGroup / imgGroup = le joueur
     * est lui-même dans le groupe « admin » / « img ».
     */
    record Settings(Quotas own, int maxServers, int ramMb, int cpu, boolean admin, boolean adminGroup,
                    boolean imgGroup) {}

    CompletableFuture<Settings> settings(UUID uuid);

    /** Quotas propres au joueur : clés MAX_SERVERS, TOTAL_RAM, TOTAL_CPU ; valeur null = retirée */
    CompletableFuture<Void> setUserQuotas(UUID uuid, java.util.Map<String, Integer> set);

    /** Crée le groupe s'il n'existe pas (avec ce poids) */
    CompletableFuture<Void> ensureGroup(String group, int weight);

    CompletableFuture<Quotas> groupQuotas(String group);

    /** Mêmes clés que setUserQuotas */
    CompletableFuture<Void> setGroupQuotas(String group, java.util.Map<String, Integer> set);

    /** Le joueur est-il lui-même dans ce groupe ? */
    CompletableFuture<Boolean> inGroup(UUID uuid, String group);
}
