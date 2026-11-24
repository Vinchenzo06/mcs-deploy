package vinch.img.farmmod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lot 73 : le filet de sécurité du lot 72 coupe une fonction au lieu de planter le jeu */
class GuardTest {

    @Test
    void missingMethodDisablesOnlyThatFeature() {
        int[] runs = {0};
        Guard.run("test-cadre", () -> {
            runs[0]++;
            throw new NoSuchMethodError("GuiGraphicsExtractor.text");
        });
        Guard.run("test-cadre", () -> runs[0]++);
        assertTrue(Guard.off("test-cadre"), "coupé");
        assertEquals(1, runs[0]);
        Guard.run("test-autre", () -> runs[0]++);
        assertFalse(Guard.off("test-autre"), "le reste continue");
        assertEquals(2, runs[0]);
    }

    @Test
    void repeatedErrorsDisableWithoutCrashing() {
        for (int i = 0; i < 25; i++) {
            Guard.run("test-comptage", () -> {
                throw new IllegalStateException("x");
            });
        }
        assertTrue(Guard.off("test-comptage"), "coupé après 20 erreurs");
    }

    @Test
    void callGivesTheFallback() {
        assertEquals("rien", Guard.call("test-suggestions", () -> {
            throw new NoClassDefFoundError("net/minecraft/Foo");
        }, "rien"));
        Guard.command("stop", () -> {
            throw new NoSuchFieldError("x");
        }); // une commande ne plante jamais
        Guard.flush();
        assertFalse(Guard.off("stop"), "une commande n'est jamais coupée");
    }
}
