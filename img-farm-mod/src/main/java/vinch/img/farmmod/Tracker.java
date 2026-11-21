package vinch.img.farmmod;

import java.util.Map;
import java.util.Objects;

/**
 * Comptage d'une session, côté client : sans rien de Minecraft, pour pouvoir le tester seul.
 * À chaque tick, le mod donne ce que le joueur voit (observe) : où il est (contexte), combien il
 * a de l'item compté (inventaire + curseur) et, devant un coffre de collecte, combien ce coffre
 * en contient. Mêmes règles que le plugin img-farm :
 *
 *  - Rien d'ouvert : ce qui arrive dans l'inventaire est ramassé au sol et compte, SAUF ce que le
 *    joueur avait lui-même jeté ou posé (« crédit ») : jeter puis reprendre ne compte pas deux fois,
 *    poser un bloc puis le recasser non plus.
 *  - Inventaire ouvert : ce qui en sort (glissé hors de la fenêtre = jeté) va au crédit ; ce qui y
 *    entre (fabrication 2×2) ne compte pas.
 *  - Four, fumoir, haut fourneau, alambic, villageois (à la main) : la différence d'inventaire
 *    compte (sortir 20 lingots = +20 ; y mettre l'item compté = −).
 *  - Coffre de collecte (ferme auto) : ce qui y est apparu depuis la dernière visite + ce qui y
 *    entre pendant qu'il est ouvert. Ce que le joueur y range ou en retire ne change rien.
 *  - Coffres ordinaires, établi, enclume… : rien ne compte (ranger, fabriquer n'est pas farmer).
 *  - Créatif, spectateur : rien ne compte.
 *  Un changement d'écran ou de joueur (mort, changement de monde) repart de l'état présent.
 */
final class Tracker {

    enum Ctx { NONE, INVENTORY, PRODUCER, COLLECT, OTHER, IGNORE }

    /** Ticks avant de lire un coffre de collecte qui vient de s'ouvrir (son contenu arrive du serveur) */
    static final int SETTLE_TICKS = 3;

    /** Ce que la session doit garder entre deux parties (crédit, contenu connu des coffres) */
    interface Store {
        long credit();

        void credit(long value);

        Map<String, Long> chestSeen();

        void add(long amount);
    }

    private final Store store;

    private Ctx ctx;
    private Object screenId;          // identité de l'écran (menu + joueur) : change = on repart de zéro
    private String chestKey;
    private long lastPlayer = -1;
    private long lastContainer = -1;
    private long playerAtOpen;
    private long rawContainer = -1;   // COLLECT : dernière lecture du coffre (même avant le réglage)
    private long rawPlayer = -1;
    private int ticksOpen;
    private boolean settled;

    Tracker(Store store) {
        this.store = store;
    }

    /** Oublie l'état du tick précédent (pause, reprise, déconnexion) */
    void reset() {
        leaveChest();
        ctx = null;
        screenId = null;
        chestKey = null;
        lastPlayer = -1;
        lastContainer = -1;
    }

    /**
     * @param ctx        où est le joueur
     * @param screenId   identité de l'écran ouvert et du joueur (un nouvel objet = un nouvel écran)
     * @param player     nombre de l'item compté chez le joueur (inventaire + curseur)
     * @param chestKey   COLLECT : clé du coffre de collecte, sinon null
     * @param container  COLLECT : nombre de l'item compté dans le coffre, sinon -1
     */
    void observe(Ctx ctx, Object screenId, long player, String chestKey, long container) {
        if (ctx != this.ctx || !Objects.equals(screenId, this.screenId) || !Objects.equals(chestKey, this.chestKey)) {
            leaveChest();
            this.ctx = ctx;
            this.screenId = screenId;
            this.chestKey = chestKey;
            lastPlayer = player;
            lastContainer = -1;
            playerAtOpen = player;
            rawContainer = ctx == Ctx.COLLECT ? container : -1;
            rawPlayer = player;
            ticksOpen = 0;
            settled = false;
            return;
        }
        long d = player - lastPlayer;
        switch (ctx) {
            case NONE -> {
                if (d > 0) {
                    long fromCredit = Math.min(d, store.credit());
                    store.credit(store.credit() - fromCredit);
                    store.add(d - fromCredit);
                } else if (d < 0) {
                    store.credit(store.credit() - d);
                }
            }
            case INVENTORY -> {
                if (d < 0) store.credit(store.credit() - d);
            }
            case PRODUCER -> store.add(d);
            case COLLECT -> {
                ticksOpen++;
                rawContainer = container;
                rawPlayer = player;
                if (!settled) {
                    if (ticksOpen < SETTLE_TICKS) return; // garde lastPlayer : la différence sera comptée au réglage
                    settle(container, player);
                } else {
                    long sum = container + player;
                    long before = lastContainer + lastPlayer;
                    if (sum > before) store.add(sum - before);
                    lastContainer = container;
                }
            }
            case OTHER, IGNORE -> { }
        }
        lastPlayer = player;
    }

    /** Première lecture du coffre ouvert : ce qui est apparu depuis la dernière visite compte */
    private void settle(long container, long player) {
        settled = true;
        Long seen = store.chestSeen().get(chestKey);
        if (seen != null) {
            long delta = (container - seen) + (player - playerAtOpen);
            if (delta > 0) store.add(delta);
        }
        lastContainer = container;
        lastPlayer = player;
    }

    /** On quitte un coffre de collecte : on retient ce qu'il contient */
    private void leaveChest() {
        if (ctx != Ctx.COLLECT || chestKey == null) return;
        if (!settled) {
            // Fermé très vite : lu seulement si le contenu était déjà arrivé
            if (ticksOpen < 1 || rawContainer < 0) return;
            if (rawContainer == 0 && store.chestSeen().getOrDefault(chestKey, 0L) > 0) return;
            settle(rawContainer, rawPlayer);
        }
        if (lastContainer >= 0) store.chestSeen().put(chestKey, lastContainer);
    }
}
