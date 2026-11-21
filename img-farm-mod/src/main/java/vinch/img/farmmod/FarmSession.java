package vinch.img.farmmod;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Une session de farm (ou de construction), comme dans le plugin img-farm. Gardée dans
 * config/img-farm-session.json : une partie fermée ou un plantage ne la perd pas (elle revient en pause).
 */
final class FarmSession implements Tracker.Store {

    static final int MIN_SECONDS = 30;

    String kind = "farm";              // farm | construction
    boolean auto;                      // farm : ferme auto (coffres de collecte) ou à la main
    String part;                       // construction : base | module
    String item;                       // farm : identifiant (ex. iron_ingot)
    String itemName;                   // nom français renvoyé par le site
    long targetCount;                  // 0 = aucun
    long targetSeconds;                // 0 = aucun
    long startedAt = System.currentTimeMillis();
    String type;                       // type de méthode du site (main, champ, ferme…)
    String server;                     // « solo » ou nom du serveur (jamais une adresse IP)
    String mcVersion;

    long quantity;
    long creditAmount;                 // items jetés ou posés par le joueur : les reprendre ne compte pas
    Set<String> containers = new LinkedHashSet<>();          // coffres de collecte : « dimension|x,y,z »
    Map<String, Long> seen = new LinkedHashMap<>();          // contenu connu de chaque coffre de collecte

    long activeMs;
    long resumedAt = System.currentTimeMillis();             // 0 = en pause
    boolean finished;
    long endedAt;

    boolean farm() {
        return "farm".equals(kind);
    }

    boolean paused() {
        return resumedAt == 0;
    }

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
        if (!farm()) return false;
        return (targetCount > 0 && quantity >= targetCount) || (targetSeconds > 0 && activeSeconds() >= targetSeconds);
    }

    // ---------- Tracker.Store ----------

    @Override
    public long credit() {
        return creditAmount;
    }

    @Override
    public void credit(long value) {
        creditAmount = Math.max(0, value);
    }

    @Override
    public Map<String, Long> chestSeen() {
        return seen;
    }

    @Override
    public void add(long amount) {
        quantity += amount;
    }
}
