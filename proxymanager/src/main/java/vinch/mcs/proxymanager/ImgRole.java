package vinch.mcs.proxymanager;

import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Rôle IMG (lot 51) : groupe LuckPerms « img » donné aux membres du site IMG Farm DB (compte
 * Minecraft lié, non bloqué) et retiré aux autres, à chaque connexion. Ses quotas (méta
 * max-servers, total-ram, total-cpu) se règlent sur le site ; des quotas posés sur un joueur
 * passent par-dessus. Poids 40 : au-dessus de default, vip et premium (ses quotas l'emportent),
 * sous host et admin. Site injoignable = rien ne change.
 */
public class ImgRole {

    public static final String GROUP = "img";
    public static final int WEIGHT = 40;

    private final RankSource lp;
    private final FarmdbMembers members;
    private final Logger logger;

    public ImgRole(RankSource lp, FarmdbMembers members, Logger logger) {
        this.lp = lp;
        this.members = members;
        this.logger = logger;
    }

    public CompletableFuture<Void> ensureGroup() {
        return lp.ensureGroup(GROUP, WEIGHT).exceptionally(e -> {
            logger.warn("Groupe LuckPerms {} impossible à créer : {}", GROUP, e.getMessage());
            return null;
        });
    }

    /** Met le groupe à jour selon le site ; ne échoue jamais */
    public CompletableFuture<Void> sync(UUID uuid, String name) {
        return CompletableFuture.supplyAsync(() -> members.check(uuid))
                .thenCompose(member -> {
                    if (member == null) {
                        return CompletableFuture.completedFuture((Void) null);
                    }
                    return lp.inGroup(uuid, GROUP).thenCompose(in -> {
                        if (in == member) {
                            return CompletableFuture.completedFuture((Void) null);
                        }
                        logger.info("Rôle IMG {} pour {}", member ? "donné" : "retiré", name);
                        return lp.setGroup(uuid, GROUP, member);
                    });
                })
                .exceptionally(e -> {
                    logger.warn("Rôle IMG de {} : {}", name, e.getMessage());
                    return null;
                });
    }
}
