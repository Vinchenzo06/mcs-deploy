package vinch.img.farm;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Material;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Une session de farm (ou de construction) d'un joueur, gardée en mémoire tant que le serveur tourne. */
final class FarmSession {

    enum Kind { FARM, CONSTRUCTION }

    final UUID player;
    final Kind kind;
    final boolean auto;            // farm : ferme auto (coffres de collecte) ou à la main
    final String part;             // construction : base | module
    final Material item;           // farm : item compté
    final String itemKey;          // ex. iron_ingot
    final String itemName;         // nom français renvoyé par le site
    final long targetCount;        // 0 = aucun
    final long targetSeconds;      // 0 = aucun
    final long startedAt = System.currentTimeMillis();

    /** Coffres de collecte (ferme auto) : clés « monde:x,y,z » de leur inventaire */
    final Set<String> containers = new LinkedHashSet<>();

    long quantity;
    String type;                   // type de méthode du site (main, champ, ferme…)
    BossBar bar;

    private long activeMs;
    private long resumedAt = System.currentTimeMillis();
    boolean finished;
    long endedAt;

    // À la main : inventaire d'un coffre ouvert (sorties - dépôts)
    int countAtOpen = -1;
    long pickupsSinceOpen;
    long fullWarnedAt;

    FarmSession(UUID player, Kind kind, boolean auto, String part, Material item, String itemKey, String itemName,
                long targetCount, long targetSeconds) {
        this.player = player;
        this.kind = kind;
        this.auto = auto;
        this.part = part;
        this.item = item;
        this.itemKey = itemKey;
        this.itemName = itemName;
        this.targetCount = targetCount;
        this.targetSeconds = targetSeconds;
        this.type = auto ? "ferme" : "main";
    }

    boolean paused() {
        return resumedAt == 0;
    }

    /** En cours : ni en pause, ni terminée */
    boolean running() {
        return !finished && !paused();
    }

    void pause() {
        if (paused() || finished) return;
        activeMs += System.currentTimeMillis() - resumedAt;
        resumedAt = 0;
    }

    void resume() {
        if (!paused() || finished) return;
        resumedAt = System.currentTimeMillis();
    }

    void finish() {
        pause();
        finished = true;
        endedAt = System.currentTimeMillis();
        countAtOpen = -1;
    }

    long activeMillis() {
        return activeMs + (paused() ? 0 : System.currentTimeMillis() - resumedAt);
    }

    long activeSeconds() {
        return activeMillis() / 1000;
    }

    /** Items par heure, ou -1 si trop tôt pour le dire */
    double ratePerHour() {
        long s = activeSeconds();
        return s < 10 ? -1 : Math.max(0, quantity) * 3600.0 / s;
    }

    boolean targetReached() {
        if (kind != Kind.FARM) return false;
        return (targetCount > 0 && quantity >= targetCount) || (targetSeconds > 0 && activeSeconds() >= targetSeconds);
    }
}
