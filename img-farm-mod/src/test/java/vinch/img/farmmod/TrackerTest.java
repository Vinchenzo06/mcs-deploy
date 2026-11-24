package vinch.img.farmmod;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Lot 73 : règles de comptage du mod (mêmes que le plugin img-farm), sans Minecraft */
class TrackerTest {

    /** Session minimale : total, crédit (jeté ou posé), contenu connu des coffres */
    static final class Store implements Tracker.Store {
        long total;
        long credit;
        final Map<String, Long> seen = new HashMap<>();

        public long credit() {
            return credit;
        }

        public void credit(long value) {
            credit = Math.max(0, value);
        }

        public Map<String, Long> chestSeen() {
            return seen;
        }

        public void add(long amount) {
            total += amount;
        }
    }

    private Store store;
    private Tracker t;
    private final Object world = new Object();

    @BeforeEach
    void setUp() {
        store = new Store();
        t = new Tracker(store);
    }

    private void none(long player) {
        t.observe(Tracker.Ctx.NONE, world, player, null, -1);
    }

    @Test
    void pickingUpCounts() {
        none(0);
        none(5);
        none(12);
        assertEquals(12, store.total);
    }

    @Test
    void droppingThenPickingUpDoesNotCountTwice() {
        none(10);
        none(4);   // jette 6
        none(10);  // les reprend
        assertEquals(0, store.total);
        none(13);  // 3 nouveaux
        assertEquals(3, store.total);
    }

    @Test
    void craftingInTheInventoryDoesNotCount() {
        Object inv = new Object();
        none(0);
        t.observe(Tracker.Ctx.INVENTORY, inv, 0, null, -1);
        t.observe(Tracker.Ctx.INVENTORY, inv, 4, null, -1); // fabriqué dans la grille 2×2
        assertEquals(0, store.total);
        t.observe(Tracker.Ctx.INVENTORY, inv, 1, null, -1); // 3 glissés hors de la fenêtre = jetés
        none(1);
        none(4);                                             // repris : ne compte pas
        assertEquals(0, store.total);
    }

    @Test
    void furnaceOutputCounts() {
        Object furnace = new Object();
        t.observe(Tracker.Ctx.PRODUCER, furnace, 0, null, -1);
        t.observe(Tracker.Ctx.PRODUCER, furnace, 20, null, -1); // 20 lingots sortis
        t.observe(Tracker.Ctx.PRODUCER, furnace, 16, null, -1); // 4 remis dedans
        assertEquals(16, store.total);
    }

    @Test
    void ordinaryChestsAndCreativeDoNotCount() {
        Object chest = new Object();
        t.observe(Tracker.Ctx.OTHER, chest, 0, null, -1);
        t.observe(Tracker.Ctx.OTHER, chest, 64, null, -1);
        t.observe(Tracker.Ctx.IGNORE, world, 64, null, -1);
        t.observe(Tracker.Ctx.IGNORE, world, 128, null, -1);
        assertEquals(0, store.total);
    }

    private void collect(Object screen, long player, long chest) {
        t.observe(Tracker.Ctx.COLLECT, screen, player, "overworld:1,64,1", chest);
    }

    @Test
    void collectionChestCountsWhatAppearedSinceTheLastVisit() {
        Object first = new Object();
        for (int i = 0; i <= Tracker.SETTLE_TICKS; i++) collect(first, 0, 100); // 1re visite : point de départ
        assertEquals(0, store.total);
        collect(first, 0, 110);  // la ferme en ajoute 10 pendant qu'il est ouvert
        assertEquals(10, store.total);
        collect(first, 30, 80);  // le joueur en retire 30 : ne change rien
        assertEquals(10, store.total);
        none(30);                 // fermé (le coffre contient 80)
        Object second = new Object();
        for (int i = 0; i <= Tracker.SETTLE_TICKS; i++) collect(second, 30, 130); // 50 de plus à la visite suivante
        assertEquals(60, store.total);
    }

    @Test
    void changingScreenStartsFromThePresentState() {
        none(10);
        t.observe(Tracker.Ctx.NONE, new Object(), 50, null, -1); // mort, changement de monde…
        assertEquals(0, store.total);
    }
}
