package vinch.mcs.lobby.commands;

import com.fasterxml.jackson.databind.JsonNode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import vinch.mcs.lobby.McsLobbyPlugin;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServerCommand implements CommandExecutor {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9-]{3,32}$");
    private static final Pattern RAM_PATTERN =
            Pattern.compile("^(\\d+(?:[.,]\\d+)?)\\s*(g|go|gb|m|mo|mb)?$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> TYPES = Set.of("PAPER", "SPIGOT", "FABRIC", "FORGE", "VANILLA");

    // Mêmes valeurs que l'API (ServerService.MIN_RAM_MB)
    private static final int MIN_RAM_MB = 1024;
    private static final int DEFAULT_RAM_MB = 2048;

    private final McsLobbyPlugin plugin;

    public ServerCommand(McsLobbyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Cette commande est réservée aux joueurs.", NamedTextColor.RED));
            return true;
        }

        // Filet de sécurité : une erreur du plugin s'affiche clairement en jeu et
        // sa trace complète part dans le journal du lobby
        try {
            dispatch(player, args);
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "/mcs " + String.join(" ", args) + " (" + player.getName() + ") a planté", t);
            player.sendMessage(Component.text("MCS » Erreur interne du plugin (" + t.getClass().getSimpleName()
                    + "). Préviens un admin.").color(NamedTextColor.RED));
        }
        return true;
    }

    private void dispatch(Player player, String[] args) {
        if (args.length == 0) {
            sendUsage(player);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> handleCreate(player, args);
            case "list" -> handleList(player);
            case "delete" -> handleDelete(player, args);
            case "join" -> handleJoin(player, args);
            case "start" -> handleStart(player, args);
            case "stop" -> handleStop(player, args);
            case "restart" -> handleRestart(player, args);
            case "quota" -> handleQuota(player);
            case "info" -> handleInfo(player, args);
            default -> sendUsage(player);
        }
    }

    // ============================================================ style ====
    //
    // Tous les messages suivent le même modèle :
    //   MCS » <texte>                      message court (gris = en cours, vert = réussi, rouge = échec)
    //   ━━━━━━ Titre ━━━━━━                 en-tête des vues (aide, liste, infos, quota)
    //   [Bouton]                           cliquable, avec une bulle d'explication
    // Les noms de serveurs sont cliquables et ouvrent /mcs info.

    private static final Component PREFIX = Component.text()
            .append(Component.text("MCS", NamedTextColor.GOLD, TextDecoration.BOLD))
            .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
            .build();

    private static void send(Player player, Component message) {
        player.sendMessage(PREFIX.append(message));
    }

    private static void pending(Player player, Component message) {
        send(player, message.colorIfAbsent(NamedTextColor.GRAY));
    }

    private static void success(Player player, Component message) {
        send(player, Component.text("✔ ", NamedTextColor.GREEN).append(message.colorIfAbsent(NamedTextColor.GREEN)));
    }

    private static void failure(Player player, Component message) {
        send(player, Component.text("✖ ", NamedTextColor.RED).append(message.colorIfAbsent(NamedTextColor.RED)));
    }

    private static void notice(Player player, Component message) {
        send(player, message.colorIfAbsent(NamedTextColor.YELLOW));
    }

    private static Component text(String s) {
        return Component.text(s);
    }

    /** Nom de serveur cliquable : ouvre ses infos */
    private static Component serverName(String name) {
        return Component.text(name, NamedTextColor.WHITE, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/mcs info " + name))
                .hoverEvent(HoverEvent.showText(Component.text("Voir les infos de " + name, NamedTextColor.GRAY)));
    }

    private static Component header(String title) {
        return Component.text("━━━━━━ ", NamedTextColor.DARK_GRAY)
                .append(Component.text(title, NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" ━━━━━━", NamedTextColor.DARK_GRAY));
    }

    private static Component button(String text, NamedTextColor color, String command, String hover) {
        return Component.text("[" + text + "]", color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    /** Bouton qui écrit la commande dans le chat sans l'envoyer (à compléter) */
    private static Component suggestButton(String text, NamedTextColor color, String command, String hover) {
        return Component.text("[" + text + "]", color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    private static Component buttons(Component... list) {
        Component line = Component.text("   ");
        for (int i = 0; i < list.length; i++) {
            line = line.append(i == 0 ? Component.empty() : Component.text(" ")).append(list[i]);
        }
        return line;
    }

    private static Component joinButton(String name) {
        return button("Rejoindre", NamedTextColor.GREEN, "/mcs join " + name, "Aller sur " + name);
    }

    private static Component startButton(String name) {
        return button("Démarrer", NamedTextColor.GREEN, "/mcs start " + name, "Démarrer " + name);
    }

    private static Component infoButton(String name) {
        return button("Infos", NamedTextColor.AQUA, "/mcs info " + name, "État, joueurs, CPU, RAM et disque de " + name);
    }

    private static Component listButton() {
        return button("Mes serveurs", NamedTextColor.AQUA, "/mcs list", "Voir tous tes serveurs");
    }

    private static Component quotaButton() {
        return button("Mon quota", NamedTextColor.AQUA, "/mcs quota", "Voir ce qu'il te reste pour créer des serveurs");
    }

    private static Component createButton() {
        return suggestButton("Créer un serveur", NamedTextColor.GREEN, "/mcs create ",
                "Exemple : /mcs create paper 1.21.4 survie 4G 2");
    }

    /** "Utilisation : /mcs join <nom>" ; un clic écrit la commande dans le chat */
    private static void usage(Player player, String syntax, String suggestion) {
        send(player, Component.text("Utilisation : ", NamedTextColor.RED)
                .append(Component.text(syntax, NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.suggestCommand(suggestion))
                        .hoverEvent(HoverEvent.showText(Component.text("Cliquer pour écrire la commande", NamedTextColor.GRAY)))));
    }

    /** État d'un serveur : symbole et texte colorés */
    private static Component state(String status, String health) {
        return switch (status) {
            case "RUNNING" -> "unhealthy".equals(health)
                    ? Component.text("● En ligne, ne répond plus", NamedTextColor.GOLD)
                    : Component.text("● En ligne", NamedTextColor.GREEN);
            case "STARTING" -> Component.text("◐ Démarrage…", NamedTextColor.YELLOW);
            case "CREATING" -> Component.text("◐ Création…", NamedTextColor.YELLOW);
            case "STOPPING" -> Component.text("◐ Arrêt en cours…", NamedTextColor.YELLOW);
            case "STOPPED" -> Component.text("○ Arrêté", NamedTextColor.GRAY);
            case "ERROR" -> Component.text("✖ Erreur", NamedTextColor.RED);
            default -> Component.text(status, NamedTextColor.GRAY);
        };
    }

    /** Juste le symbole de l'état (●, ○, ◐, ✖), le texte complet au survol */
    private static Component stateIcon(String status) {
        Component full = state(status, null);
        String symbol = switch (status) {
            case "RUNNING" -> "●";
            case "STOPPED" -> "○";
            case "ERROR" -> "✖";
            default -> "◐";
        };
        return Component.text(symbol).color(full.color()).hoverEvent(HoverEvent.showText(full));
    }

    private static String cores(long n) {
        return n + (n > 1 ? " cœurs" : " cœur");
    }

    // ============================================================ erreurs ====

    /** Message lisible à partir d'une erreur de l'API (sans "java.lang...", ni code HTTP) */
    private static String errorText(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof java.net.http.HttpTimeoutException) {
            return "Pas de réponse à temps. Réessaie dans un instant.";
        }
        if (t instanceof java.io.IOException) {
            return "Le service MCS est injoignable pour le moment. Réessaie dans un instant.";
        }
        String msg = t.getMessage() == null ? "erreur inconnue" : t.getMessage();
        return msg.replaceFirst("^API erreur \\(\\d+\\) : ", "")
                .replaceFirst("^(Erreur démarrage|Erreur arrêt|Échec création côté agent) : ", "");
    }

    private static boolean notFound(String msg) {
        return msg.contains("introuvable") || msg.contains("non trouvé") || msg.contains("(404)");
    }

    /**
     * Affiche une erreur de l'API en clair, avec le bouton utile quand il y en a un.
     * Les erreurs inattendues sont aussi écrites dans la console du lobby.
     */
    private void reportError(Player player, Throwable error, String serverName) {
        String msg = errorText(error);

        if (msg.contains("Joueur") && notFound(msg)) {
            failure(player, text("Ton profil MCS est introuvable. Reconnecte-toi au réseau."));
        } else if (msg.contains("Limite de serveurs atteinte")) {
            failure(player, text("Tu as atteint ta limite de serveurs."));
            player.sendMessage(buttons(listButton(), quotaButton()));
        } else if (msg.startsWith("Pas assez de RAM")) {
            Matcher m = Pattern.compile("Demandé : (\\d+) Mo, budget restant : (-?\\d+) Mo").matcher(msg);
            failure(player, text(m.find()
                    ? "Pas assez de RAM dans ton quota : tu demandes " + size(Long.parseLong(m.group(1)))
                      + ", il te reste " + size(Math.max(0, Long.parseLong(m.group(2)))) + "."
                    : msg));
            player.sendMessage(buttons(quotaButton()));
        } else if (msg.startsWith("Pas assez de CPU")) {
            Matcher m = Pattern.compile("Demandé : (\\d+) vcore\\(s\\), budget restant : (-?\\d+)").matcher(msg);
            failure(player, text(m.find()
                    ? "Pas assez de CPU dans ton quota : tu demandes " + cores(Long.parseLong(m.group(1)))
                      + ", il te reste " + cores(Math.max(0, Long.parseLong(m.group(2)))) + "."
                    : msg));
            player.sendMessage(buttons(quotaButton()));
        } else if (msg.contains("déjà un serveur nommé")) {
            failure(player, text("Tu as déjà un serveur nommé ").append(serverName(serverName)).append(text(".")));
        } else if (msg.contains("Aucune machine n'a assez de place")) {
            failure(player, text("Aucune machine n'a assez de place pour ce serveur en ce moment. "
                    + "Essaie avec moins de RAM ou de CPU, ou réessaie plus tard."));
        } else if (msg.contains("Aucune node disponible")) {
            failure(player, text("Aucune machine n'est en ligne pour le moment. Réessaie plus tard."));
        } else if (msg.contains("déjà en marche")) {
            notice(player, text("").append(serverName(serverName)).append(text(" est déjà en ligne.")));
            player.sendMessage(buttons(joinButton(serverName), infoButton(serverName)));
        } else if (msg.contains("déjà arrêté")) {
            notice(player, text("").append(serverName(serverName)).append(text(" est déjà arrêté.")));
            player.sendMessage(buttons(startButton(serverName)));
        } else if (msg.contains("propriétaire")) {
            failure(player, text("Ce serveur ne t'appartient pas."));
        } else if (notFound(msg)) {
            failure(player, text(serverName == null ? "Introuvable." : "Tu n'as pas de serveur nommé " + serverName + "."));
            player.sendMessage(buttons(listButton()));
        } else {
            failure(player, text(msg));
            if (serverName != null) {
                player.sendMessage(buttons(infoButton(serverName)));
            }
            plugin.getLogger().warning("/mcs (" + player.getName() + ") : " + msg);
        }
    }

    // ============================================================ aide ====

    private static Component helpLine(String syntax, String suggestion, String description, String hover) {
        return Component.text(" " + syntax, NamedTextColor.AQUA)
                .append(Component.text("  " + description, NamedTextColor.GRAY))
                .clickEvent(ClickEvent.suggestCommand(suggestion))
                .hoverEvent(HoverEvent.showText(Component.text(hover + "\n", NamedTextColor.GRAY)
                        .append(Component.text("Cliquer pour écrire la commande", NamedTextColor.DARK_GRAY))));
    }

    private void sendUsage(Player player) {
        player.sendMessage(Component.empty());
        player.sendMessage(header("MCS"));
        player.sendMessage(helpLine("/mcs create <type> <version> <nom> [ram] [cpu]", "/mcs create ",
                "Créer un serveur",
                "Types : Paper, Spigot, Fabric, Forge, Vanilla\n"
                        + "RAM : 2G par défaut, 1G minimum (en Go « 4G » ou en Mo « 4096 »)\n"
                        + "CPU : 1 cœur par défaut\n"
                        + "Limités par ton quota (/mcs quota)"));
        player.sendMessage(helpLine("/mcs list", "/mcs list", "Tes serveurs", "Liste de tes serveurs et leur état"));
        player.sendMessage(helpLine("/mcs info <nom>", "/mcs info ", "État et ressources",
                "Joueurs, CPU, RAM et disque d'un serveur"));
        player.sendMessage(helpLine("/mcs join <nom>", "/mcs join ", "Rejoindre", "Aller sur un de tes serveurs"));
        player.sendMessage(helpLine("/mcs start|stop|restart <nom>", "/mcs start ", "Allumer, éteindre",
                "Démarrer, arrêter ou redémarrer un serveur"));
        player.sendMessage(helpLine("/mcs delete <nom>", "/mcs delete ", "Supprimer",
                "Supprime le serveur et son monde, définitivement\n(une confirmation est demandée)"));
        player.sendMessage(helpLine("/mcs quota", "/mcs quota", "Tes limites",
                "Serveurs, RAM et CPU que tu peux encore utiliser"));
        player.sendMessage(Component.text(" Exemple : ", NamedTextColor.DARK_GRAY)
                .append(Component.text("/mcs create paper 1.21.4 survie 4G 2", NamedTextColor.GRAY)
                        .clickEvent(ClickEvent.suggestCommand("/mcs create paper 1.21.4 survie 4G 2"))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Paper 1.21.4, nommé « survie », 4 Go de RAM, 2 cœurs", NamedTextColor.GRAY)))));
    }

    // ============================================================ create ====

    /** "4G", "4 Go", "1.5G" -> Go ; "4096", "4096M" -> Mo ; un nombre seul ≤ 64 est lu en Go */
    static Integer parseRamMb(String input) {
        Matcher m = RAM_PATTERN.matcher(input.trim());
        if (!m.matches()) {
            return null;
        }
        double value = Double.parseDouble(m.group(1).replace(',', '.'));
        String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
        boolean go = unit.startsWith("g") || (unit.isEmpty() && value <= 64);
        long mb = Math.round(go ? value * 1024 : value);
        return mb > Integer.MAX_VALUE ? null : (int) mb;
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length < 4 || args.length > 6) {
            usage(player, "/mcs create <type> <version> <nom> [ram] [cpu]", "/mcs create ");
            send(player, Component.text("Exemple : /mcs create paper 1.21.4 survie 4G 2  (RAM par défaut 2G, CPU 1)",
                    NamedTextColor.GRAY));
            return;
        }

        String type = args[1].toUpperCase();
        String version = args[2];
        String name = args[3].toLowerCase();
        int ramMb = DEFAULT_RAM_MB;
        int cpuCores = 1;

        if (!TYPES.contains(type)) {
            failure(player, text("Type inconnu : " + args[1] + ". Choisis parmi Paper, Spigot, Fabric, Forge, Vanilla."));
            return;
        }

        if (!NAME_PATTERN.matcher(name).matches()) {
            failure(player, text("Nom invalide : 3 à 32 caractères, lettres minuscules, chiffres et tirets."));
            return;
        }

        if (args.length >= 5) {
            // Pas de maximum ici : l'API vérifie le quota du joueur et la place sur les machines
            Integer parsed = parseRamMb(args[4]);
            if (parsed == null) {
                failure(player, text("RAM invalide : écris-la en Go (4G) ou en Mo (4096)."));
                return;
            }
            if (parsed < MIN_RAM_MB) {
                failure(player, text("RAM minimum : " + size(MIN_RAM_MB) + "."));
                return;
            }
            ramMb = parsed;
        }

        if (args.length == 6) {
            try {
                cpuCores = Integer.parseInt(args[5]);
            } catch (NumberFormatException e) {
                cpuCores = 0;
            }
            if (cpuCores < 1) {
                failure(player, text("CPU invalide : un nombre entier de cœurs, 1 minimum."));
                return;
            }
        }

        pending(player, text("Création de ").append(Component.text(name, NamedTextColor.WHITE))
                .append(text(" en cours… (" + capitalize(type) + " " + version + ", " + size(ramMb) + ", "
                        + cores(cpuCores) + ") — la première fois peut prendre quelques minutes.")));

        final int finalRamMb = ramMb;
        final int finalCpuCores = cpuCores;

        plugin.syncPlayerLimits(player.getUniqueId(), player.getName())
                .thenCompose(v -> plugin.getApiClient().getPlayerByUuid(player.getUniqueId()))
                .thenCompose(playerInfo -> plugin.getApiClient().createServer(
                        playerInfo.get("id").asLong(), name, name, type, version, finalRamMb, finalCpuCores, 0))
                .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        reportError(player, error, name);
                        return;
                    }

                    String serverName = result.path("name").asText(name);
                    long storage = result.path("storageMb").asLong(0);
                    success(player, text("Ton serveur ").append(serverName(serverName)).append(text(" est prêt !")));
                    player.sendMessage(Component.text("   " + capitalize(type) + " " + version
                            + "  ·  RAM " + size(finalRamMb) + "  ·  " + cores(finalCpuCores)
                            + (storage > 0 ? "  ·  Disque " + size(storage) : ""), NamedTextColor.GRAY));
                    player.sendMessage(buttons(joinButton(serverName), infoButton(serverName)));
                }));
    }

    // ============================================================ list ====

    private void handleList(Player player) {
        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
                .thenCompose(playerInfo -> plugin.getApiClient().getPlayerServers(playerInfo.get("id").asLong()))
                .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        reportError(player, error, null);
                        return;
                    }

                    JsonNode servers = result.path("servers");
                    player.sendMessage(Component.empty());
                    player.sendMessage(header("Tes serveurs"));

                    if (servers.size() == 0) {
                        player.sendMessage(Component.text(" Tu n'as encore aucun serveur.", NamedTextColor.GRAY));
                        player.sendMessage(buttons(createButton(), quotaButton()));
                        return;
                    }

                    for (JsonNode s : servers) {
                        String name = s.path("name").asText();
                        String status = s.path("status").asText("?");
                        Component line = Component.text(" ")
                                .append(stateIcon(status))
                                .append(text(" "))
                                .append(serverName(name))
                                .append(Component.text("  " + capitalize(s.path("serverType").asText("")) + " "
                                        + s.path("minecraftVersion").asText("")
                                        + (s.has("allocatedRamMb") ? "  ·  " + size(s.path("allocatedRamMb").asLong()) : "")
                                        + (s.has("allocatedCpuCores") ? "  ·  " + cores(s.path("allocatedCpuCores").asLong()) : ""),
                                        NamedTextColor.GRAY))
                                .append(text("  "));
                        if ("RUNNING".equals(status)) {
                            line = line.append(joinButton(name));
                        } else if ("STOPPED".equals(status) || "ERROR".equals(status)) {
                            line = line.append(startButton(name));
                        }
                        player.sendMessage(line);
                    }
                    player.sendMessage(Component.text(" Clique sur un nom pour voir ses infos.  ", NamedTextColor.DARK_GRAY)
                            .append(quotaButton()));
                }));
    }

    // ============================================================ delete ====

    private void handleDelete(Player player, String[] args) {
        if (args.length < 2 || args.length > 3) {
            usage(player, "/mcs delete <nom>", "/mcs delete ");
            return;
        }
        String name = args[1].toLowerCase();

        // Suppression définitive : on demande d'abord une confirmation
        if (args.length == 2 || !"confirm".equalsIgnoreCase(args[2])) {
            notice(player, text("⚠ Supprimer ").append(serverName(name))
                    .append(text(" ? Son monde, ses plugins et ses mods seront effacés définitivement.")));
            player.sendMessage(buttons(
                    button("Oui, supprimer", NamedTextColor.RED, "/mcs delete " + name + " confirm",
                            "Supprimer " + name + " pour de bon"),
                    button("Annuler", NamedTextColor.GRAY, "/mcs info " + name, "Garder " + name)));
            return;
        }

        pending(player, text("Suppression de ").append(Component.text(name, NamedTextColor.WHITE)).append(text("…")));

        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
                .thenCompose(playerInfo -> plugin.getApiClient().deleteServer(name, playerInfo.get("id").asLong()))
                .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        reportError(player, error, name);
                        return;
                    }
                    success(player, text("Serveur ").append(Component.text(name, NamedTextColor.WHITE))
                            .append(text(" supprimé.")));
                }));
    }

    // ============================================================ join ====

    private void handleJoin(Player player, String[] args) {
        if (args.length != 2) {
            usage(player, "/mcs join <nom>", "/mcs join ");
            return;
        }
        String name = args[1].toLowerCase();

        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
                .thenCompose(playerInfo -> plugin.getApiClient().getServerByOwnerAndName(playerInfo.get("id").asLong(), name))
                .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        reportError(player, error, name);
                        return;
                    }

                    String status = result.path("status").asText("?");
                    switch (status) {
                        case "RUNNING" -> {
                            pending(player, text("Connexion à ").append(Component.text(name, NamedTextColor.WHITE))
                                    .append(text("…")));
                            sendToServer(player, result.get("velocityName").asText());
                        }
                        case "STARTING", "CREATING" -> {
                            notice(player, text("").append(serverName(name)).append(text(" démarre encore, réessaie dans un instant.")));
                            player.sendMessage(buttons(joinButton(name), infoButton(name)));
                        }
                        default -> {
                            failure(player, text("").append(serverName(name)).append(text(" n'est pas en ligne (")).append(state(status, null))
                                    .append(Component.text(").", NamedTextColor.RED)));
                            if ("STOPPED".equals(status) || "ERROR".equals(status)) {
                                player.sendMessage(buttons(startButton(name)));
                            }
                        }
                    }
                }));
    }

    // ============================================================ start / stop / restart ====

    private void handleStart(Player player, String[] args) {
        if (args.length != 2) {
            usage(player, "/mcs start <nom>", "/mcs start ");
            return;
        }
        String name = args[1].toLowerCase();
        pending(player, text("Démarrage de ").append(Component.text(name, NamedTextColor.WHITE))
                .append(text("… (jusqu'à 2 minutes)")));

        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
                .thenCompose(playerInfo -> plugin.getApiClient().startServer(name, playerInfo.get("id").asLong()))
                .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        reportError(player, error, name);
                        return;
                    }
                    // L'agent ne répond qu'une fois le serveur prêt à accueillir des joueurs
                    success(player, text("").append(serverName(name)).append(text(" est en ligne !")));
                    player.sendMessage(buttons(joinButton(name), infoButton(name)));
                }));
    }

    private void handleStop(Player player, String[] args) {
        if (args.length != 2) {
            usage(player, "/mcs stop <nom>", "/mcs stop ");
            return;
        }
        String name = args[1].toLowerCase();
        pending(player, text("Arrêt de ").append(Component.text(name, NamedTextColor.WHITE)).append(text("…")));

        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
                .thenCompose(playerInfo -> plugin.getApiClient().stopServer(name, playerInfo.get("id").asLong()))
                .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        reportError(player, error, name);
                        return;
                    }
                    success(player, text("").append(serverName(name)).append(text(" est arrêté.")));
                    player.sendMessage(buttons(startButton(name)));
                }));
    }

    private void handleRestart(Player player, String[] args) {
        if (args.length != 2) {
            usage(player, "/mcs restart <nom>", "/mcs restart ");
            return;
        }
        String name = args[1].toLowerCase();
        pending(player, text("Redémarrage de ").append(Component.text(name, NamedTextColor.WHITE))
                .append(text("… (jusqu'à 2 minutes)")));

        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
                .thenCompose(playerInfo -> plugin.getApiClient().restartServer(name, playerInfo.get("id").asLong()))
                .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        reportError(player, error, name);
                        return;
                    }
                    success(player, text("").append(serverName(name)).append(text(" a redémarré !")));
                    player.sendMessage(buttons(joinButton(name), infoButton(name)));
                }));
    }

    // ============================================================ info ====

    private void handleInfo(Player player, String[] args) {
        if (args.length != 2) {
            usage(player, "/mcs info <nom>", "/mcs info ");
            return;
        }
        String name = args[1].toLowerCase();

        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
                .thenCompose(playerInfo -> plugin.getApiClient()
                        .getServerByOwnerAndName(playerInfo.get("id").asLong(), name))
                .thenCompose(server -> plugin.getApiClient().getServerStats(server.get("id").asLong()))
                .whenComplete((stats, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        reportError(player, error, name);
                        return;
                    }
                    sendInfo(player, stats);
                }));
    }

    private static final int BAR_LENGTH = 20;

    /** Barre de progression colorée : vert < 70 %, jaune < 90 %, rouge au-delà */
    private static Component bar(double ratio) {
        double r = Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(r * BAR_LENGTH);
        NamedTextColor color = r < 0.7 ? NamedTextColor.GREEN : r < 0.9 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        return Component.text("|".repeat(filled)).color(color)
                .append(Component.text("|".repeat(BAR_LENGTH - filled)).color(NamedTextColor.DARK_GRAY));
    }

    /** 2048 -> "2 Go", 1998 -> "2,0 Go", 800 -> "800 Mo" */
    private static String size(long mb) {
        if (mb < 1024) {
            return mb + " Mo";
        }
        double go = mb / 1024.0;
        return (go == Math.floor(go) ? String.valueOf((long) go) : String.format(Locale.FRANCE, "%.1f", go)) + " Go";
    }

    private static String percent(double ratio) {
        return String.format(Locale.FRANCE, "%.0f %%", ratio * 100);
    }

    // La police de Minecraft est proportionnelle : les barres (toutes de la même
    // largeur) viennent en premier pour rester alignées, le libellé ensuite
    private static Component label(String text) {
        return Component.text("  " + text + "  ").color(NamedTextColor.GRAY);
    }

    /** "depuis 2 h 05" à partir de la date de démarrage (heure du VPS, comme le lobby) */
    private static String uptime(String startedAt) {
        try {
            java.time.Duration d = java.time.Duration.between(
                    java.time.LocalDateTime.parse(startedAt), java.time.LocalDateTime.now());
            long min = Math.max(0, d.toMinutes());
            if (min < 60) {
                return min + " min";
            }
            if (min < 24 * 60) {
                return String.format("%d h %02d", min / 60, min % 60);
            }
            return String.format("%d j %d h", min / (24 * 60), (min / 60) % 24);
        } catch (Exception e) {
            return null;
        }
    }

    private void sendInfo(Player player, JsonNode s) {
        String name = s.path("name").asText();
        String status = s.path("status").asText("?");
        boolean running = "RUNNING".equals(status);

        // En-tête : nom, état, type et version, durée en ligne
        player.sendMessage(Component.empty());
        player.sendMessage(header(name));
        Component line = Component.text(" ").append(state(status, s.path("health").asText("unknown")))
                .append(Component.text("  ·  " + capitalize(s.path("serverType").asText("?")) + " "
                        + s.path("minecraftVersion").asText(""), NamedTextColor.GRAY));
        String up = running && s.hasNonNull("lastStartedAt") ? uptime(s.get("lastStartedAt").asText()) : null;
        if (up != null) {
            line = line.append(Component.text("  ·  depuis " + up, NamedTextColor.GRAY));
        }
        player.sendMessage(line);

        // Joueurs
        int players = s.path("players").asInt(0);
        player.sendMessage(Component.text(" Joueurs : ", NamedTextColor.GRAY)
                .append(Component.text(players + (players > 1 ? " connectés" : " connecté"),
                        players > 0 ? NamedTextColor.AQUA : NamedTextColor.WHITE)));

        boolean measured = s.has("memUsedMb") && running;

        // CPU : rapporté aux cœurs alloués (docker compte 100 % par cœur)
        int cpuCores = Math.max(1, s.path("allocatedCpuCores").asInt(1));
        if (measured) {
            double cpuRaw = s.path("cpuPercent").asDouble(0);
            double cpu = cpuRaw / 100.0 / cpuCores;
            player.sendMessage(Component.text(" ").append(bar(cpu)).append(label("CPU"))
                    .append(Component.text(percent(cpu) + " de " + cores(cpuCores), NamedTextColor.WHITE))
                    .hoverEvent(HoverEvent.showText(Component.text(String.format(Locale.FRANCE,
                            "%.1f %% d'un cœur, sur %s alloué(s)", cpuRaw, cores(cpuCores)), NamedTextColor.GRAY))));
        }

        // RAM : utilisée réellement / RAM du serveur (limite stricte, marge de Java comprise)
        long allocated = s.path("allocatedRamMb").asLong(0);
        if (measured && allocated > 0) {
            long used = s.path("memUsedMb").asLong(0);
            long heap = s.path("javaHeapMb").asLong(0);
            String hover = "Mémoire utilisée / RAM du serveur.\n"
                    + "Le serveur ne peut jamais dépasser " + size(allocated) + ".";
            if (heap > 0 && heap < allocated) {
                hover += "\nDont " + size(heap) + " pour le monde, les joueurs et les mods (tas Java)\n"
                        + "et " + size(allocated - heap) + " pour Java lui-même (classes, threads, réseau).";
            }
            player.sendMessage(Component.text(" ").append(bar((double) used / allocated)).append(label("RAM"))
                    .append(Component.text(size(used) + " / " + size(allocated), NamedTextColor.WHITE))
                    .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY))));
        }

        // Disque : mesuré toutes les 10 minutes
        long quota = s.path("allocatedStorageMb").asLong(0);
        if (s.hasNonNull("diskUsedMb") && quota > 0) {
            long disk = s.path("diskUsedMb").asLong(0);
            boolean over = s.path("diskQuotaExceeded").asBoolean(false);
            player.sendMessage(Component.text(" ").append(bar((double) disk / quota)).append(label("Disque"))
                    .append(Component.text(size(disk) + " / " + size(quota), NamedTextColor.WHITE))
                    .append(over ? Component.text("  quota dépassé", NamedTextColor.RED) : Component.empty())
                    .hoverEvent(HoverEvent.showText(Component.text(
                            "Espace utilisé par le monde, les plugins et les mods.\nMesuré toutes les 10 minutes.",
                            NamedTextColor.GRAY))));
        } else if (quota > 0) {
            player.sendMessage(Component.text(" Disque : ", NamedTextColor.GRAY)
                    .append(Component.text("mesure en cours… (quota " + size(quota) + ")", NamedTextColor.DARK_GRAY)));
        }

        if (!measured && running) {
            player.sendMessage(Component.text(" Mesures disponibles dans moins de 30 s", NamedTextColor.DARK_GRAY));
        }

        // Actions
        Component actions = Component.text(" ");
        if (running) {
            actions = actions.append(joinButton(name))
                    .append(text(" "))
                    .append(button("Redémarrer", NamedTextColor.YELLOW, "/mcs restart " + name, "Redémarrer " + name))
                    .append(text(" "))
                    .append(button("Arrêter", NamedTextColor.RED, "/mcs stop " + name, "Arrêter " + name));
        } else if ("STOPPED".equals(status) || "ERROR".equals(status)) {
            actions = actions.append(startButton(name))
                    .append(text(" "))
                    .append(button("Supprimer", NamedTextColor.DARK_RED, "/mcs delete " + name,
                            "Supprimer " + name + " (une confirmation est demandée)"));
        }
        actions = actions.append(text(" "))
                .append(button("Actualiser", NamedTextColor.AQUA, "/mcs info " + name, "Mettre à jour ces informations"));
        player.sendMessage(actions);
    }

    private static String capitalize(String type) {
        if (type == null || type.isEmpty()) {
            return type;
        }
        return type.charAt(0) + type.substring(1).toLowerCase();
    }

    // ============================================================ quota ====

    private void handleQuota(Player player) {
        plugin.syncPlayerLimits(player.getUniqueId(), player.getName())
                .thenCompose(v -> plugin.getApiClient().getPlayerByUuid(player.getUniqueId()))
                .thenCompose(playerInfo -> plugin.getApiClient().getPlayerQuota(playerInfo.get("id").asLong()))
                .whenComplete((q, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        reportError(player, error, null);
                        return;
                    }
                    sendQuota(player, q);
                }));
    }

    private void sendQuota(Player player, JsonNode q) {
        long maxServers = q.path("maxServers").asLong(0);
        long serversUsed = q.path("serversUsed").asLong(0);
        long totalRam = q.path("totalRamMb").asLong(0);
        long ramUsed = q.path("ramUsedMb").asLong(0);
        long ramLeft = Math.max(0, q.path("ramRemainingMb").asLong(totalRam - ramUsed));
        long totalCpu = q.path("totalCpuCores").asLong(0);
        long cpuUsed = q.path("cpuUsedCores").asLong(0);
        long cpuLeft = Math.max(0, q.path("cpuRemainingCores").asLong(totalCpu - cpuUsed));

        player.sendMessage(Component.empty());
        player.sendMessage(header("Ton quota"));

        // Même présentation que /mcs info : barre, libellé, valeurs
        player.sendMessage(Component.text(" ").append(bar(ratio(serversUsed, maxServers))).append(label("Serveurs"))
                .append(Component.text(serversUsed + " / " + maxServers, NamedTextColor.WHITE))
                .append(Component.text(serversUsed >= maxServers ? "  limite atteinte" : "  reste " + (maxServers - serversUsed),
                        serversUsed >= maxServers ? NamedTextColor.RED : NamedTextColor.GRAY))
                .hoverEvent(HoverEvent.showText(Component.text(
                        "Nombre de serveurs que tu peux posséder,\nallumés ou éteints.", NamedTextColor.GRAY))));

        player.sendMessage(Component.text(" ").append(bar(ratio(ramUsed, totalRam))).append(label("RAM"))
                .append(Component.text(size(ramUsed) + " / " + size(totalRam), NamedTextColor.WHITE))
                .append(Component.text("  reste " + size(ramLeft), ramLeft < MIN_RAM_MB ? NamedTextColor.RED : NamedTextColor.GRAY))
                .hoverEvent(HoverEvent.showText(Component.text(
                        "RAM réservée par tes serveurs, allumés ou éteints.\n"
                                + "Chaque serveur reçoit exactement la RAM choisie à sa création.", NamedTextColor.GRAY))));

        player.sendMessage(Component.text(" ").append(bar(ratio(cpuUsed, totalCpu))).append(label("CPU"))
                .append(Component.text(cpuUsed + " / " + cores(totalCpu), NamedTextColor.WHITE))
                .append(Component.text("  reste " + cpuLeft, cpuLeft < 1 ? NamedTextColor.RED : NamedTextColor.GRAY))
                .hoverEvent(HoverEvent.showText(Component.text(
                        "Cœurs réservés par tes serveurs, allumés ou éteints.", NamedTextColor.GRAY))));

        // Ce que le joueur peut encore créer
        boolean canCreate = serversUsed < maxServers && ramLeft >= MIN_RAM_MB && cpuLeft >= 1;
        if (canCreate) {
            player.sendMessage(Component.text(" Plus gros serveur possible : ", NamedTextColor.GRAY)
                    .append(Component.text(size(ramLeft) + " · " + cores(cpuLeft), NamedTextColor.AQUA))
                    .hoverEvent(HoverEvent.showText(Component.text(
                            "Selon ton quota. Il faut aussi qu'une machine ait la place.", NamedTextColor.GRAY))));
            player.sendMessage(buttons(listButton(), createButton()));
        } else {
            player.sendMessage(Component.text(" Tu ne peux plus créer de serveur : supprime-en un ou demande plus à un admin.",
                    NamedTextColor.GRAY));
            player.sendMessage(buttons(listButton()));
        }
    }

    private static double ratio(long used, long total) {
        return total <= 0 ? (used > 0 ? 1 : 0) : (double) used / total;
    }

    // Demande au proxy (plugin proxymanager) d'envoyer le joueur vers ce serveur
    private void sendToServer(Player player, String serverName) {
        player.sendPluginMessage(plugin, "mcs:connect",
                serverName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
