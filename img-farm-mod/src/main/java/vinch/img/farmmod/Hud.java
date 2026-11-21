package vinch.img.farmmod;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/** Le cadre de la session en haut à gauche (comme la barre du plugin) ; /imgfarm hud le cache */
final class Hud {

    private Hud() {
    }

    static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        ImgFarmMod mod = ImgFarmMod.INSTANCE;
        if (mod == null || !mod.config.hud) return;
        FarmSession s = mod.session;
        Minecraft mc = Minecraft.getInstance();
        if (s == null || mc.player == null) return;

        List<String> lines = new ArrayList<>();
        int color;
        if (s.finished) {
            lines.add("IMG · session terminée");
            lines.add("/imgfarm envoyer ou /imgfarm jeter");
            color = 0xFFFFD866;
        } else if (s.farm()) {
            lines.add("IMG · " + s.itemName + (s.auto ? " (ferme auto)" : "") + (s.paused() ? " · en pause" : ""));
            long secs = s.activeSeconds();
            double rate = s.ratePerHour();
            lines.add(Msg.num(s.quantity) + (s.targetCount > 0 ? " / " + Msg.num(s.targetCount) : "")
                    + "  ·  " + Msg.clock(secs) + (s.targetSeconds > 0 ? " / " + Msg.clock(s.targetSeconds) : "")
                    + "  ·  " + (rate < 0 ? "…" : Msg.num(rate) + " /h"));
            if (s.auto) {
                long toOpen = s.containers.stream().filter(k -> !s.seen.containsKey(k)).count();
                lines.add(s.containers.size() + (s.containers.size() > 1 ? " coffres" : " coffre")
                        + (toOpen > 0 ? " · " + toOpen + " à ouvrir une 1re fois" : ""));
            }
            color = s.paused() ? 0xFFAAAAAA : 0xFF7CF29A;
        } else {
            lines.add("IMG · construction " + ("module".equals(s.part) ? "d'un module" : "de la base") + (s.paused() ? " · en pause" : ""));
            lines.add(Msg.clock(s.activeSeconds()));
            color = s.paused() ? 0xFFAAAAAA : 0xFF7CF29A;
        }

        Font font = mc.font;
        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        int x = 4, y = 4, lh = font.lineHeight + 2;
        g.fill(x - 2, y - 2, x + w + 2, y + lines.size() * lh, 0x90000000);
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, lines.get(i), x, y + i * lh, i == 0 ? color : 0xFFFFFFFF, true);
        }
    }
}
