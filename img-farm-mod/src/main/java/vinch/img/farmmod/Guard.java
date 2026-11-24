package vinch.img.farmmod;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Lot 72 : filet de sécurité pour les nouvelles versions de Minecraft. Le fichier compilé pour une
 * version se charge aussi sur les suivantes (dépendance « minecraft >= ») ; si le jeu a renommé ou
 * retiré ce que le mod utilise, l'appel lance une LinkageError (NoSuchMethodError,
 * NoClassDefFoundError…). Au lieu de planter le jeu, la fonction touchée est coupée, le joueur est
 * prévenu une fois dans le chat et le reste du mod continue.
 * Une exception ordinaire répétée (20 fois) coupe aussi la fonction : jamais de spam ni de plantage.
 */
final class Guard {

    private static final int MAX_ERRORS = 20;
    private static final Set<String> OFF = ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> ERRORS = new ConcurrentHashMap<>();
    /** Message à montrer dans le chat dès que possible (le chat lui-même peut être touché) */
    private static volatile String pending;

    private Guard() {
    }

    static boolean off(String feature) {
        return OFF.contains(feature);
    }

    static Set<String> disabled() {
        return Set.copyOf(OFF);
    }

    static void run(String feature, Runnable action) {
        if (OFF.contains(feature)) return;
        try {
            action.run();
        } catch (LinkageError e) {
            disable(feature, e);
        } catch (RuntimeException e) {
            failed(feature, e);
        }
    }

    static <T> T call(String feature, Supplier<T> action, T fallback) {
        if (OFF.contains(feature)) return fallback;
        try {
            return action.get();
        } catch (LinkageError e) {
            disable(feature, e);
        } catch (RuntimeException e) {
            failed(feature, e);
        }
        return fallback;
    }

    /** Une commande : jamais coupée pour de bon, mais une erreur ne plante jamais le jeu */
    static void command(String name, Runnable action) {
        try {
            action.run();
        } catch (LinkageError e) {
            ImgFarmMod.LOG.error("IMG Farm : /imgfarm {} ne marche pas sur Minecraft {} (mod à mettre à jour)",
                    name, mcVersion(), e);
            tell("/imgfarm " + name + " ne marche pas sur cette version de Minecraft : prends la dernière version du mod.");
        } catch (RuntimeException e) {
            ImgFarmMod.LOG.error("IMG Farm : erreur dans /imgfarm {}", name, e);
            tell("Erreur dans /imgfarm " + name + " : " + e.getClass().getSimpleName());
        }
    }

    static void disable(String feature, Throwable e) {
        if (!OFF.add(feature)) return;
        ImgFarmMod.LOG.error("IMG Farm : « {} » désactivé sur Minecraft {} (mod {}) : à mettre à jour",
                feature, mcVersion(), modVersion(), e);
        pending = "IMG Farm : « " + feature + " » ne marche pas sur Minecraft " + mcVersion()
                + " et a été coupé (le reste du mod marche). Prends la dernière version du mod : Mon profil → Mod Fabric.";
    }

    private static void failed(String feature, RuntimeException e) {
        int n = ERRORS.merge(feature, 1, Integer::sum);
        if (n == 1) ImgFarmMod.LOG.error("IMG Farm : erreur dans « {} »", feature, e);
        if (n >= MAX_ERRORS) disable(feature, e);
    }

    /** Appelé à chaque tick : montre le message en attente (sans jamais planter lui-même) */
    static void flush() {
        String msg = pending;
        if (msg == null) return;
        pending = null;
        tell(msg);
    }

    private static void tell(String msg) {
        try {
            ImgFarmMod.say(Msg.error(msg));
        } catch (LinkageError | RuntimeException e) {
            ImgFarmMod.LOG.warn("IMG Farm : {}", msg);
        }
    }

    private static String mcVersion() {
        ImgFarmMod m = ImgFarmMod.INSTANCE;
        return m == null ? "?" : m.mcVersion;
    }

    private static String modVersion() {
        ImgFarmMod m = ImgFarmMod.INSTANCE;
        return m == null ? "?" : m.modVersion;
    }
}
