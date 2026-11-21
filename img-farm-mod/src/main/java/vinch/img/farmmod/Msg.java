package vinch.img.farmmod;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Messages du chat (mêmes couleurs que le plugin) et petits formats */
final class Msg {

    private static final Pattern DURATION = Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)(?:min|m))?(?:(\\d+)s)?$");

    private Msg() {
    }

    static MutableComponent prefix() {
        return Component.literal("[IMG] ").withStyle(ChatFormatting.DARK_GREEN);
    }

    static MutableComponent ok(String s) {
        return prefix().append(Component.literal(s).withStyle(ChatFormatting.GREEN));
    }

    static MutableComponent info(String s) {
        return prefix().append(Component.literal(s).withStyle(ChatFormatting.GRAY));
    }

    static MutableComponent error(String s) {
        return prefix().append(Component.literal(s).withStyle(ChatFormatting.RED));
    }

    /** [texte] cliquable qui lance une commande du mod */
    static MutableComponent button(String label, String command, String hover, ChatFormatting color) {
        return Component.literal("[" + label + "]").withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    /** [texte] cliquable qui ouvre une page du site */
    static MutableComponent link(String label, String url, ChatFormatting color) {
        MutableComponent c = Component.literal(label).withStyle(color);
        if (!(url.startsWith("https://") || url.startsWith("http://"))) return c;
        try {
            URI uri = URI.create(url);
            return c.withStyle(style -> style
                    .withClickEvent(new ClickEvent.OpenUrl(uri))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(url))));
        } catch (IllegalArgumentException e) {
            return c;
        }
    }

    static MutableComponent line(String yellow, String gray) {
        return Component.literal("  " + yellow).withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" " + gray).withStyle(ChatFormatting.GRAY));
    }

    static String num(double n) {
        return String.format(Locale.ROOT, "%,d", Math.round(n)).replace(',', ' ');
    }

    static String clock(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    static String humanDuration(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return h + " h " + String.format(Locale.ROOT, "%02d", m);
        if (m > 0) return m + " min" + (s > 0 ? " " + String.format(Locale.ROOT, "%02d", s) + " s" : "");
        return s + " s";
    }

    /** « 30m », « 1h30 », « 90s » → secondes ; 0 si invalide ou plus de 48 h */
    static long parseDuration(String t) {
        if (t.isEmpty()) return 0;
        Matcher hm = Pattern.compile("^(\\d+)h(\\d+)$").matcher(t);
        if (hm.matches()) return Long.parseLong(hm.group(1)) * 3600 + Long.parseLong(hm.group(2)) * 60;
        if (!t.matches(".*[hms].*")) return 0;
        Matcher m = DURATION.matcher(t);
        if (!m.matches()) return 0;
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long min = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
        long s = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
        long total = h * 3600 + min * 60 + s;
        return total > 48 * 3600 ? 0 : total;
    }
}
