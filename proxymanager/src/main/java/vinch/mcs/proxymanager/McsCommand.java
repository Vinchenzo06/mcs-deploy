package vinch.mcs.proxymanager;

import com.fasterxml.jackson.databind.JsonNode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Commande /mcs, enregistrée sur le proxy : utilisable depuis le lobby ET depuis
 * n'importe quel serveur de jeu, sans plugin sur ces serveurs (Velocity traite la
 * commande avant le serveur). Les droits sont vérifiés par l'API.
 */
public class McsCommand implements SimpleCommand {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9-]{3,32}$");
    private static final Pattern RAM_PATTERN =
            Pattern.compile("^(\\d+(?:[.,]\\d+)?)\\s*(g|go|gb|m|mo|mb)?$", Pattern.CASE_INSENSITIVE);
    // Vanilla retiré (lot 35) : le proxy ne peut pas lui transmettre les vrais comptes des joueurs
    private static final Set<String> TYPES = Set.of("PAPER", "SPIGOT", "FABRIC", "FORGE", "NEOFORGE");
    private static final List<String> JAVA_VERSIONS = List.of("8", "11", "16", "17", "21", "25");

    // Mêmes valeurs que l'API (ServerService.MIN_RAM_MB)
    private static final int MIN_RAM_MB = 1024;
    private static final int DEFAULT_RAM_MB = 2048;

    private final ProxyServer server;
    private final ApiClient apiClient;
    private final PlayerSync playerSync;
    private final Logger logger;

    public McsCommand(ProxyServer server, ApiClient apiClient, PlayerSync playerSync, Logger logger) {
        this.server = server;
        this.apiClient = apiClient;
        this.playerSync = playerSync;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("Cette commande est réservée aux joueurs.", NamedTextColor.RED));
            return;
        }
        String[] args = invocation.arguments();
        // Filet de sécurité : une erreur du plugin s'affiche clairement en jeu et
        // sa trace complète part dans le journal du proxy
        try {
            dispatch(player, args);
        } catch (Throwable t) {
            logger.error("/mcs " + String.join(" ", args) + " (" + player.getUsername() + ") a planté", t);
            player.sendMessage(Component.text("MCS » Erreur interne du plugin (" + t.getClass().getSimpleName()
                    + "). Préviens un admin.").color(NamedTextColor.RED));
        }
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<String>> suggestAsync(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of());
        }
        return completer.suggest(player, invocation.arguments());
    }

    private final McsTabCompleter completer = new McsTabCompleter(this);

    ProxyServer proxy() {
        return server;
    }

    ApiClient api() {
        return apiClient;
    }

    /** Exécute un rappel d'API en journalisant toute erreur inattendue */
    private void run(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            logger.error("/mcs : erreur dans un rappel", t);
        }
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
            case "start", "stop", "restart" -> handlePower(player, args);
            case "quota" -> handleQuota(player);
            case "info" -> handleInfo(player, args);
            case "members", "membres" -> handleMembers(player, args);
            case "invite" -> handleInvite(player, args);
            case "remove" -> handleRemove(player, args);
            case "leave" -> handleLeave(player, args);
            case "public", "private" -> handleVisibility(player, args);
            case "console" -> handleConsole(player, args);
            case "display" -> handleDisplay(player, args);
            case "host" -> handleHost(player, args);
            case "backup" -> handleBackup(player, args);
            case "backups" -> handleBackups(player, args);
            case "restore" -> handleRestore(player, args);
            case "migrate" -> handleMigrate(player, args);
            case "regions" -> handleRegions(player);
            case "machine" -> handleMachine(player, args);
            case "move" -> handleMove(player, args);
            case "java" -> handleJava(player, args);
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
        Throwable t = unwrap(error);
        if (t instanceof java.net.http.HttpTimeoutException) {
            return "Pas de réponse à temps. Réessaie dans un instant.";
        }
        if (t instanceof java.io.IOException) {
            return "Le service MCS est injoignable pour le moment. Réessaie dans un instant.";
        }
        String msg = t.getMessage() == null ? "erreur inconnue" : t.getMessage();
        return msg.replaceFirst("^API erreur \\(\\d+\\) : ", "")
                .replaceFirst("^(Erreur démarrage|Erreur arrêt|Échec création côté agent) : ", "")
                .trim();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /** Refus normal de l'API (4xx avec une explication) : rien à écrire dans la console */
    private static boolean apiRefusal(Throwable error) {
        String m = unwrap(error).getMessage();
        return m != null && m.matches("(?s)^API erreur \\(4\\d\\d\\) : .+");
    }

    /**
     * Affiche une erreur de l'API en clair, avec le bouton utile quand il y en a un.
     * Les erreurs inattendues sont aussi écrites dans la console du lobby.
     */
    private void reportError(Player player, Throwable error, String serverName) {
        if (unwrap(error) instanceof ApiClient.ApiException ae && ae.body().has("javaIssue")) {
            showJavaIssue(player, ae.body(), serverName);
            return;
        }
        String msg = errorText(error);

        if (msg.isEmpty() || msg.startsWith("Joueur non trouvé")) {
            // Profil absent de l'API (404 sans corps)
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
        } else if (msg.contains("déjà en marche") && serverName != null) {
            notice(player, text("").append(serverName(serverName)).append(text(" est déjà en ligne.")));
            player.sendMessage(buttons(joinButton(serverName), infoButton(serverName)));
        } else if (msg.contains("déjà arrêté") && serverName != null) {
            notice(player, text("").append(serverName(serverName)).append(text(" est déjà arrêté.")));
            player.sendMessage(buttons(startButton(serverName)));
        } else if (msg.startsWith("Serveur '") && msg.endsWith("introuvable")) {
            failure(player, text("Aucun serveur « " + serverName + " » trouvé parmi les tiens et ceux où tu es invité."));
            player.sendMessage(buttons(listButton()));
        } else {
            // Les refus de l'API sont déjà des phrases en français (droits, joueur inconnu...)
            failure(player, text(msg));
            if (!apiRefusal(error)) {
                logger.warn("/mcs (" + player.getUsername() + ") : " + msg);
            }
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
        player.sendMessage(helpLine("/mcs list", "/mcs list", "Tes serveurs",
                "Tes serveurs et ceux où tu es invité"));
        player.sendMessage(helpLine("/mcs info <serveur>", "/mcs info ", "État et ressources",
                "Joueurs, CPU, RAM et disque d'un serveur"));
        player.sendMessage(helpLine("/mcs join <serveur>", "/mcs join ", "Rejoindre", "Aller sur un serveur"));
        player.sendMessage(helpLine("/mcs start|stop|restart <serveur>", "/mcs start ", "Allumer, éteindre",
                "Démarrer, arrêter ou redémarrer un serveur"));
        player.sendMessage(helpLine("/mcs members <serveur>", "/mcs members ", "Invités et accès",
                "Qui a accès au serveur, avec quel rôle"));
        player.sendMessage(helpLine("/mcs invite <serveur> <joueur> [rôle]", "/mcs invite ", "Inviter",
                "Rôles : membre (rejoindre), gerant (+ démarrer/arrêter),\n"
                        + "technicien (+ console et fichiers). Membre par défaut.\n"
                        + "Relancer la commande change le rôle."));
        player.sendMessage(helpLine("/mcs remove <serveur> <joueur>", "/mcs remove ", "Retirer un invité",
                "Le joueur n'a plus accès au serveur"));
        player.sendMessage(helpLine("/mcs public|private <serveur>", "/mcs public ", "Ouvert ou privé",
                "Public : tout le monde peut entrer\nPrivé (par défaut) : seulement toi et tes invités"));
        player.sendMessage(helpLine("/mcs display <serveur> on|off", "/mcs display ", "Rôles réseau",
                "Affiche le rôle réseau ([Admin], [VIP]...) des joueurs en préfixe\n"
                        + "(équipes vanilla). À couper si ton serveur utilise ses propres équipes."));
        player.sendMessage(helpLine("/mcs console <serveur> <commande>", "/mcs console ", "Console",
                "Exécute une commande sur le serveur, ex. : whitelist add Bob"));
        player.sendMessage(helpLine("/mcs move <joueur> <serveur>", "/mcs move ", "Amener un joueur",
                "Hébergeur de la machine et admins : envoie un joueur\nsur le serveur, une seule fois (sans lui donner d'accès)"));
        player.sendMessage(helpLine("/mcs backup|backups <serveur>", "/mcs backups ", "Sauvegardes",
                "/mcs backup <serveur> : sauvegarde manuelle\n"
                        + "/mcs backups <serveur> : historique (n°, types, expiration)\n"
                        + "/mcs backup keep <serveur> [n°] : permanente (propriétaire)\n"
                        + "/mcs backup settings <serveur> : réglages (selon le rôle)\n"
                        + "/mcs restore <serveur> <n°> : restaurer (propriétaire, admins)\n"
                        + "/mcs restore deleted : recréer un serveur supprimé\n"
                        + "Admins : /mcs backup role <rôle>, defaults, set <serveur> (exception)"));
        player.sendMessage(helpLine("/mcs migrate <serveur> [région]", "/mcs migrate ", "Déplacer",
                "Change ton serveur de machine ou de région (/mcs regions).\n"
                        + "Machine hors ligne : /mcs migrate <serveur> backup\n"
                        + "Admins : /mcs migrate <serveur> machine <n°>,\n"
                        + "/mcs machine region|backup|evacuate <n°>"));
        player.sendMessage(helpLine("/mcs host [list|set|remove]", "/mcs host ", "Hôtes (admins)",
                "Admins : choisir le joueur hôte de chaque machine.\n"
                        + "Il gère les serveurs de SA machine seulement (titre ʜᴏsᴛ)."));
        player.sendMessage(helpLine("/mcs java <serveur> [version]", "/mcs java ", "Version de Java",
                "Choisis la version de Java du serveur : 8, 11, 16, 17, 21, 25\n"
                        + "ou auto (selon la version de Minecraft)."));
        player.sendMessage(helpLine("/mcs delete <serveur>", "/mcs delete ", "Supprimer",
                "Supprime le serveur et son monde, définitivement\n(une confirmation est demandée)"));
        player.sendMessage(helpLine("/mcs quota", "/mcs quota", "Tes limites",
                "Serveurs, RAM et CPU que tu peux encore utiliser"));
        player.sendMessage(Component.text(" Serveur d'un autre joueur : ", NamedTextColor.DARK_GRAY)
                .append(Component.text("pseudo/nom", NamedTextColor.GRAY))
                .append(Component.text("  ·  Exemple : ", NamedTextColor.DARK_GRAY))
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

    private void handleCreate(Player player, String[] rawArgs) {
        // "... confirm" : accepte de supprimer les sauvegardes d'un serveur supprimé qui occupe une place
        boolean replaceDeleted = rawArgs.length >= 5 && "confirm".equalsIgnoreCase(rawArgs[rawArgs.length - 1]);
        String[] args = replaceDeleted ? java.util.Arrays.copyOf(rawArgs, rawArgs.length - 1) : rawArgs;
        String command = "/mcs " + String.join(" ", args);
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

        if ("VANILLA".equals(type)) {
            failure(player, text("Vanilla n'est plus proposé : derrière le réseau, il ne verrait pas les vrais comptes "
                    + "des joueurs (skins, UUID). Prends Paper, ou Fabric sans mods (même jeu)."));
            return;
        }
        if (!TYPES.contains(type)) {
            failure(player, text("Type inconnu : " + args[1] + ". Choisis parmi Paper, Spigot, Fabric, Forge, NeoForge."));
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

        playerSync.sync(player)
                .thenCompose(v -> apiClient.getPlayerByUuid(player.getUniqueId()))
                .thenCompose(playerInfo -> apiClient.createServer(
                        playerInfo.get("id").asLong(), name, type, version, finalRamMb, finalCpuCores, replaceDeleted))
                .whenComplete((result, error) -> run(() -> {
                    if (!player.isActive()) {
                        return;
                    }
                    if (error != null && errorText(error).startsWith("Emplacement occupé")) {
                        notice(player, text("⚠ " + errorText(error)));
                        player.sendMessage(buttons(
                                button("Créer quand même", NamedTextColor.RED, command + " confirm",
                                        "Supprime les sauvegardes du serveur supprimé et crée " + name),
                                button("Serveurs supprimés", NamedTextColor.AQUA, "/mcs restore deleted",
                                        "Voir ou recréer tes serveurs supprimés")));
                        return;
                    }
                    if (error != null) {
                        reportError(player, error, name);
                        return;
                    }

                    if ("CREATING".equals(result.path("status").asText())) {
                        notice(player, text("La création de ").append(Component.text(name, NamedTextColor.WHITE))
                                .append(text(" continue en arrière-plan (la première sur une machine peut prendre "
                                        + "plusieurs minutes). Tu recevras un message quand il sera prêt.")));
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

    // ============================================================ résolution ====

    private record Target(long playerId, JsonNode server) {
        long id() {
            return server.path("id").asLong();
        }

        /** Nom à afficher et à réutiliser dans les commandes : "survie" ou "bob/survie" */
        String ref() {
            return server.path("ref").asText(server.path("name").asText());
        }

        boolean can(String right) {
            for (JsonNode r : server.path("rights")) {
                if (right.equals(r.asText())) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Trouve le serveur désigné par le joueur ("nom" ou "pseudo/nom") avec ses droits,
     * puis lance l'action sur le thread principal. Les erreurs sont affichées.
     */
    private void withServer(Player player, String ref, java.util.function.Consumer<Target> action) {
        apiClient.getPlayerByUuid(player.getUniqueId())
                .thenCompose(info -> {
                    long playerId = info.get("id").asLong();
                    return apiClient.resolveServer(playerId, ref).thenApply(s -> new Target(playerId, s));
                })
                .whenComplete((target, error) -> run(() -> {
                    if (!player.isActive()) {
                        return;
                    }
                    if (error != null) {
                        reportError(player, error, ref.toLowerCase(Locale.ROOT));
                        return;
                    }
                    action.accept(target);
                }));
    }

    /** Réponse d'une action lancée au nom du joueur, affichée sur le thread principal */
    private void afterApi(Player player, java.util.concurrent.CompletableFuture<JsonNode> call, String ref,
                          java.util.function.Consumer<JsonNode> onSuccess) {
        call.whenComplete((result, error) -> run(() -> {
            if (!player.isActive()) {
                return;
            }
            if (error != null) {
                reportError(player, error, ref);
                return;
            }
            onSuccess.accept(result);
        }));
    }

    private static final Pattern PLAYER_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private static boolean validPlayerName(Player player, String name) {
        if (PLAYER_PATTERN.matcher(name).matches()) {
            return true;
        }
        failure(player, text("Pseudo invalide : " + name));
        return false;
    }

    private static String relationLabel(String relation) {
        return switch (relation) {
            case "proprietaire" -> "propriétaire";
            case "gerant" -> "gérant";
            case "hebergeur" -> "hébergeur (ta machine)";
            default -> relation;
        };
    }

    // ============================================================ list ====

    private void handleList(Player player) {
        apiClient.getPlayerByUuid(player.getUniqueId())
                .thenCompose(playerInfo -> apiClient.getPlayerServers(playerInfo.get("id").asLong()))
                .whenComplete((result, error) -> run(() -> {
                    if (error != null) {
                        reportError(player, error, null);
                        return;
                    }

                    JsonNode servers = result.path("servers");
                    JsonNode shared = result.path("shared");
                    player.sendMessage(Component.empty());
                    player.sendMessage(header("Tes serveurs"));

                    if (servers.size() == 0) {
                        player.sendMessage(Component.text(" Tu n'as encore aucun serveur.", NamedTextColor.GRAY));
                    }
                    for (JsonNode s : servers) {
                        player.sendMessage(listLine(s, s.path("name").asText(), null, true));
                    }

                    if (shared.size() > 0) {
                        player.sendMessage(Component.text(" Partagés avec toi", NamedTextColor.GOLD));
                        for (JsonNode s : shared) {
                            boolean canJoin = false;
                            boolean canPower = false;
                            for (JsonNode r : s.path("rights")) {
                                canJoin |= "JOIN".equals(r.asText());
                                canPower |= "POWER".equals(r.asText());
                            }
                            player.sendMessage(listLine(s, s.path("ref").asText(),
                                    relationLabel(s.path("relation").asText("")), canJoin || canPower));
                        }
                    }

                    if (servers.size() == 0 && shared.size() == 0) {
                        player.sendMessage(buttons(createButton(), quotaButton()));
                    } else {
                        player.sendMessage(Component.text(" Clique sur un nom pour voir ses infos.  ", NamedTextColor.DARK_GRAY)
                                .append(quotaButton()));
                    }
                }));
    }

    /** "● survie  Paper 1.21.4 · 4 Go · 2 cœurs  [Rejoindre]" (+ rôle pour un serveur partagé) */
    private static Component listLine(JsonNode s, String ref, String role, boolean withButton) {
        String status = s.path("status").asText("?");
        Component line = Component.text(" ")
                .append(stateIcon(status))
                .append(text(" "))
                .append(serverName(ref))
                .append(Component.text("  " + capitalize(s.path("serverType").asText("")) + " "
                        + s.path("minecraftVersion").asText("")
                        + (s.has("allocatedRamMb") ? "  ·  " + size(s.path("allocatedRamMb").asLong()) : "")
                        + (s.has("allocatedCpuCores") ? "  ·  " + cores(s.path("allocatedCpuCores").asLong()) : ""),
                        NamedTextColor.GRAY));
        if (role != null) {
            line = line.append(Component.text("  " + role, NamedTextColor.DARK_AQUA));
        }
        if (s.path("isPublic").asBoolean(false)) {
            line = line.append(Component.text("  public", NamedTextColor.DARK_GREEN));
        }
        line = line.append(text("  "));
        if (withButton) {
            if ("RUNNING".equals(status)) {
                line = line.append(joinButton(ref));
            } else if ("STOPPED".equals(status) || "ERROR".equals(status)) {
                line = line.append(startButton(ref));
            }
        }
        return line;
    }

    // ============================================================ delete ====

    private void handleDelete(Player player, String[] args) {
        if (args.length < 2 || args.length > 3) {
            usage(player, "/mcs delete <serveur>", "/mcs delete ");
            return;
        }
        boolean confirmed = args.length == 3 && "confirm".equalsIgnoreCase(args[2]);

        withServer(player, args[1], t -> {
            String ref = t.ref();
            if (!t.can("DELETE")) {
                failure(player, text("Seul le propriétaire du serveur peut le supprimer."));
                return;
            }
            // Suppression définitive : on demande d'abord une confirmation
            if (!confirmed) {
                notice(player, text("⚠ Supprimer ").append(serverName(ref))
                        .append(text(" ? Son monde, ses plugins et ses mods seront effacés définitivement.")));
                player.sendMessage(buttons(
                        button("Oui, supprimer", NamedTextColor.RED, "/mcs delete " + ref + " confirm",
                                "Supprimer " + ref + " pour de bon"),
                        button("Annuler", NamedTextColor.GRAY, "/mcs info " + ref, "Garder " + ref)));
                return;
            }
            pending(player, text("Suppression de ").append(Component.text(ref, NamedTextColor.WHITE)).append(text("…")));
            afterApi(player, apiClient.serverAction(t.id(), "delete", t.playerId()), ref,
                    r -> success(player, text("Serveur ").append(Component.text(ref, NamedTextColor.WHITE))
                            .append(text(" supprimé."))));
        });
    }

    // ============================================================ join ====

    private void handleJoin(Player player, String[] args) {
        if (args.length != 2) {
            usage(player, "/mcs join <serveur>", "/mcs join ");
            return;
        }
        withServer(player, args[1], t -> {
            String ref = t.ref();
            if (!t.can("JOIN")) {
                failure(player, text("Ce serveur est privé : demande une invitation à son propriétaire."));
                return;
            }
            String status = t.server().path("status").asText("?");
            switch (status) {
                case "RUNNING" -> {
                    pending(player, text("Connexion à ").append(Component.text(ref, NamedTextColor.WHITE)).append(text("…")));
                    sendToServer(player, t.server().path("velocityName").asText());
                }
                case "STARTING", "CREATING" -> {
                    notice(player, text("").append(serverName(ref)).append(text(" démarre encore, réessaie dans un instant.")));
                    player.sendMessage(buttons(joinButton(ref), infoButton(ref)));
                }
                default -> {
                    failure(player, text("").append(serverName(ref)).append(text(" n'est pas en ligne ("))
                            .append(state(status, null)).append(Component.text(").", NamedTextColor.RED)));
                    if (t.can("POWER") && ("STOPPED".equals(status) || "ERROR".equals(status))) {
                        player.sendMessage(buttons(startButton(ref)));
                    }
                }
            }
        });
    }

    // ============================================================ start / stop / restart ====

    private void handlePower(Player player, String[] args) {
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length != 2) {
            usage(player, "/mcs " + action + " <serveur>", "/mcs " + action + " ");
            return;
        }
        withServer(player, args[1], t -> {
            String ref = t.ref();
            if (!t.can("POWER")) {
                failure(player, text("Tu n'as pas le droit de démarrer ou d'arrêter ce serveur."));
                return;
            }
            String doing = switch (action) {
                case "start" -> "Démarrage de ";
                case "stop" -> "Arrêt de ";
                default -> "Redémarrage de ";
            };
            pending(player, text(doing).append(Component.text(ref, NamedTextColor.WHITE))
                    .append(text("stop".equals(action) ? "…" : "… (jusqu'à 2 minutes, plus s'il change de machine)")));
            afterApi(player, apiClient.serverAction(t.id(), action, t.playerId()), ref, r -> {
                // L'agent ne répond qu'une fois le serveur prêt à accueillir des joueurs
                switch (action) {
                    case "start" -> {
                        if ("RUNNING".equals(r.path("status").asText())) {
                            notice(player, text(ref + " démarre sur une autre machine depuis son rangement, "
                                    + "ça continue en arrière-plan : /mcs info " + ref + " dans quelques minutes."));
                            return;
                        }
                        success(player, text("").append(serverName(ref)).append(text(" est en ligne !")));
                        if (r.path("moved").asBoolean(false)) {
                            player.sendMessage(Component.text("   Démarré sur la " + r.path("target").asText("nouvelle machine")
                                    + " (sa machine habituelle était pleine ou hors ligne).", NamedTextColor.GRAY));
                        }
                        player.sendMessage(buttons(joinButton(ref), infoButton(ref)));
                    }
                    case "stop" -> {
                        success(player, text("").append(serverName(ref)).append(text(" est arrêté.")));
                        player.sendMessage(buttons(startButton(ref)));
                    }
                    default -> {
                        success(player, text("").append(serverName(ref)).append(text(" a redémarré !")));
                        player.sendMessage(buttons(joinButton(ref), infoButton(ref)));
                    }
                }
            });
        });
    }

    // ============================================================ java ====

    /** /mcs java <serveur> [8|11|16|17|21|25|auto] [start] */
    private void handleJava(Player player, String[] args) {
        if (args.length < 2 || args.length > 4) {
            usage(player, "/mcs java <serveur> [8|11|16|17|21|25|auto]", "/mcs java ");
            return;
        }
        String version = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT).replace("java", "") : null;
        if (version != null && !version.equals("auto") && !JAVA_VERSIONS.contains(version)) {
            failure(player, text("Version de Java inconnue : " + args[2] + ". Choisis parmi "
                    + String.join(", ", JAVA_VERSIONS) + " ou auto."));
            return;
        }
        boolean start = args.length == 4 && "start".equalsIgnoreCase(args[3]);
        withServer(player, args[1], t -> {
            String ref = t.ref();
            if (!t.can("FILES")) {
                failure(player, text("Seuls le propriétaire et les techniciens peuvent changer la version de Java."));
                return;
            }
            if (version == null) {
                afterApi(player, apiClient.getServerStats(t.id()), ref, s -> {
                    int current = s.path("java").asInt(0);
                    notice(player, text("").append(serverName(ref)).append(text(" utilise Java " + current
                            + (s.path("javaAuto").asBoolean(true) ? " (automatique)." : " (choisi)."))));
                    player.sendMessage(javaButtons(ref, current, 0, false));
                });
                return;
            }
            pending(player, text("Passage de ").append(Component.text(ref, NamedTextColor.WHITE))
                    .append(text(" à Java " + version + "… (le premier usage d'une version la télécharge sur la machine)")));
            afterApi(player, apiClient.setJava(t.id(), t.playerId(), version, start), ref, r -> {
                int java = r.path("java").asInt();
                String status = r.path("status").asText("");
                if (r.path("needsStart").asBoolean(false)) {
                    // Rangé au central : la version servira à ce démarrage
                    pending(player, text("Java " + java + " enregistré, démarrage de " + ref + "…"));
                    afterApi(player, apiClient.serverAction(t.id(), "start", t.playerId()), ref, x -> {
                        success(player, text("").append(serverName(ref)).append(text(" est en ligne avec Java " + java + " !")));
                        player.sendMessage(buttons(joinButton(ref), infoButton(ref)));
                    });
                    return;
                }
                switch (status) {
                    case "STARTED" -> {
                        success(player, text("").append(serverName(ref)).append(text(" est en ligne avec Java " + java + " !")));
                        player.sendMessage(buttons(joinButton(ref), infoButton(ref)));
                    }
                    case "SAVED" -> success(player, text("Java " + java + " sera utilisé au prochain démarrage de ")
                            .append(serverName(ref)).append(text(".")));
                    default -> {
                        success(player, text("").append(serverName(ref)).append(text(" utilisera Java " + java
                                + (r.path("auto").asBoolean(false) ? " (automatique)." : "."))));
                        player.sendMessage(buttons(startButton(ref)));
                    }
                }
            });
        });
    }

    /** Problème de version de Java : explication et un bouton par version */
    private void showJavaIssue(Player player, JsonNode body, String ref) {
        JsonNode issue = body.path("javaIssue");
        String server = ref != null ? ref : issue.path("server").asText("?");
        failure(player, text(body.path("error").asText("Problème de version de Java.")));
        player.sendMessage(Component.text("   Choisis la version de Java de " + server + " (le serveur redémarre avec) :",
                NamedTextColor.GRAY));
        player.sendMessage(javaButtons(server, issue.path("current").asInt(0), issue.path("suggested").asInt(0), true));
    }

    private static Component javaButtons(String ref, int current, int suggested, boolean start) {
        List<Component> list = new ArrayList<>();
        for (String v : JAVA_VERSIONS) {
            int n = Integer.parseInt(v);
            String label = "Java " + v + (n == suggested ? " ★" : "") + (n == current ? " (actuel)" : "");
            NamedTextColor color = n == suggested ? NamedTextColor.GREEN : n == current ? NamedTextColor.DARK_GRAY : NamedTextColor.AQUA;
            list.add(button(label, color, "/mcs java " + ref + " " + v + (start ? " start" : ""),
                    n == suggested ? "Conseillé d'après le journal du serveur" : "Utiliser Java " + v));
        }
        list.add(button("Auto", NamedTextColor.GRAY, "/mcs java " + ref + " auto" + (start ? " start" : ""),
                "Selon la version de Minecraft"));
        return buttons(list.toArray(new Component[0]));
    }

    // ============================================================ info ====

    private void handleInfo(Player player, String[] args) {
        if (args.length != 2) {
            usage(player, "/mcs info <serveur>", "/mcs info ");
            return;
        }
        withServer(player, args[1], t -> afterApi(player, apiClient.getServerStats(t.id()), t.ref(),
                stats -> sendInfo(player, stats, t)));
    }

    // ============================================================ membres ====

    private void handleMembers(Player player, String[] args) {
        if (args.length != 2) {
            usage(player, "/mcs members <serveur>", "/mcs members ");
            return;
        }
        withServer(player, args[1], t -> afterApi(player, apiClient.getMembers(t.id(), t.playerId()),
                t.ref(), result -> sendMembers(player, t, result.path("members"))));
    }

    private static final String[] LEVELS = {"membre", "gerant", "technicien"};

    private static String levelHover(String level) {
        return switch (level) {
            case "gerant" -> "Gérant : rejoindre, démarrer, arrêter, redémarrer";
            case "technicien" -> "Technicien : gérant + console et fichiers";
            default -> "Membre : rejoindre le serveur";
        };
    }

    private void sendMembers(Player player, Target t, JsonNode members) {
        String ref = t.ref();
        JsonNode s = t.server();
        player.sendMessage(Component.empty());
        player.sendMessage(header("Accès à " + ref));

        boolean isPublic = s.path("isPublic").asBoolean(false);
        Component access = Component.text(" Accès : ", NamedTextColor.GRAY)
                .append(isPublic
                        ? Component.text("Public", NamedTextColor.GREEN)
                                .hoverEvent(HoverEvent.showText(Component.text("Tout le monde peut entrer", NamedTextColor.GRAY)))
                        : Component.text("Privé", NamedTextColor.GOLD)
                                .hoverEvent(HoverEvent.showText(Component.text(
                                        "Seuls le propriétaire, ses invités, l'hébergeur et les admins", NamedTextColor.GRAY))))
                .append(Component.text("  ·  propriétaire " + s.path("ownerUsername").asText("?"), NamedTextColor.GRAY));
        if (t.can("VISIBILITY")) {
            access = access.append(text("  ")).append(isPublic
                    ? button("Rendre privé", NamedTextColor.GOLD, "/mcs private " + ref, "Réserver " + ref + " à tes invités")
                    : button("Rendre public", NamedTextColor.GREEN, "/mcs public " + ref, "Ouvrir " + ref + " à tout le monde"));
        }
        player.sendMessage(access);

        boolean display = s.path("showNetworkRank").asBoolean(true);
        Component ranks = Component.text(" Rôles réseau : ", NamedTextColor.GRAY)
                .append(display ? Component.text("affichés", NamedTextColor.GREEN) : Component.text("masqués", NamedTextColor.GOLD))
                .hoverEvent(HoverEvent.showText(Component.text(
                        "Préfixe [Admin], [VIP]... via les équipes vanilla du serveur", NamedTextColor.GRAY)));
        if (t.can("VISIBILITY")) {
            ranks = ranks.append(text("  ")).append(display
                    ? button("Masquer", NamedTextColor.GOLD, "/mcs display " + ref + " off",
                            "Laisser les équipes libres (mini-jeux...)")
                    : button("Afficher", NamedTextColor.GREEN, "/mcs display " + ref + " on",
                            "Afficher le rôle réseau des joueurs"));
        }
        player.sendMessage(ranks);

        if (members.size() == 0) {
            player.sendMessage(Component.text(" Aucun invité pour l'instant.", NamedTextColor.GRAY));
        }
        for (JsonNode m : members) {
            String name = m.path("username").asText();
            String level = m.path("level").asText("membre");
            Component line = Component.text(" • ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(name, NamedTextColor.WHITE))
                    .append(Component.text("  " + relationLabel(level), NamedTextColor.DARK_AQUA)
                            .hoverEvent(HoverEvent.showText(Component.text(levelHover(level), NamedTextColor.GRAY))));
            if (t.can("INVITE")) {
                for (String l : LEVELS) {
                    if (!l.equals(level)) {
                        line = line.append(text(" ")).append(button(relationLabel(l), NamedTextColor.AQUA,
                                "/mcs invite " + ref + " " + name + " " + l, "Passer " + name + " en " + levelHover(l)));
                    }
                }
            }
            if (t.can("REMOVE")) {
                line = line.append(text(" ")).append(button("Retirer", NamedTextColor.RED,
                        "/mcs remove " + ref + " " + name, "Retirer l'accès de " + name));
            }
            player.sendMessage(line);
        }

        if (t.can("INVITE")) {
            player.sendMessage(buttons(suggestButton("Inviter", NamedTextColor.GREEN, "/mcs invite " + ref + " ",
                    "Écris le pseudo, puis le rôle : membre, gerant ou technicien")));
        } else if (!"proprietaire".equals(s.path("relation").asText()) && members.size() > 0) {
            player.sendMessage(Component.text(" Seul le propriétaire invite des joueurs.", NamedTextColor.DARK_GRAY));
        }
    }

    private void handleInvite(Player player, String[] args) {
        if (args.length < 3 || args.length > 4) {
            usage(player, "/mcs invite <serveur> <joueur> [membre|gerant|technicien]", "/mcs invite ");
            return;
        }
        String target = args[2];
        String level = args.length == 4 ? args[3].toLowerCase(Locale.ROOT).replace('é', 'e') : "membre";
        if (!validPlayerName(player, target)) {
            return;
        }
        if (!java.util.Arrays.asList(LEVELS).contains(level)) {
            failure(player, text("Rôle inconnu : " + args[3] + ". Choisis membre, gerant ou technicien."));
            return;
        }
        withServer(player, args[1], t -> {
            String ref = t.ref();
            afterApi(player, apiClient.invite(t.id(), t.playerId(), target, level), ref, r -> {
                String role = relationLabel(r.path("level").asText(level));
                if (r.path("created").asBoolean(true)) {
                    success(player, text(target + " est invité sur ").append(serverName(ref))
                            .append(text(" (" + role + ").")));
                    Player invited = server.getPlayer(target).orElse(null);
                    if (invited != null) {
                        notice(invited, text(player.getUsername() + " t'a invité sur ").append(serverName(ref))
                                .append(text(" (" + role + ").")));
                        invited.sendMessage(buttons(joinButton(ref), infoButton(ref)));
                    }
                } else {
                    success(player, text(target + " est maintenant " + role + " de ").append(serverName(ref)).append(text(".")));
                }
                player.sendMessage(buttons(button("Voir les accès", NamedTextColor.AQUA, "/mcs members " + ref,
                        "Invités de " + ref)));
            });
        });
    }

    private void handleRemove(Player player, String[] args) {
        if (args.length != 3) {
            usage(player, "/mcs remove <serveur> <joueur>", "/mcs remove ");
            return;
        }
        String target = args[2];
        if (!validPlayerName(player, target)) {
            return;
        }
        withServer(player, args[1], t -> afterApi(player,
                apiClient.removeMember(t.id(), t.playerId(), target), t.ref(),
                r -> success(player, text(target + " n'a plus accès à ").append(serverName(t.ref())).append(text(".")))));
    }

    private void handleLeave(Player player, String[] args) {
        if (args.length != 2) {
            usage(player, "/mcs leave <serveur>", "/mcs leave ");
            return;
        }
        withServer(player, args[1], t -> afterApi(player,
                apiClient.removeMember(t.id(), t.playerId(), player.getUsername()), t.ref(),
                r -> success(player, text("Tu n'as plus accès à " + t.ref() + "."))));
    }

    private void handleVisibility(Player player, String[] args) {
        boolean makePublic = "public".equalsIgnoreCase(args[0]);
        if (args.length != 2) {
            usage(player, "/mcs " + (makePublic ? "public" : "private") + " <serveur>",
                    "/mcs " + (makePublic ? "public " : "private "));
            return;
        }
        withServer(player, args[1], t -> afterApi(player,
                apiClient.setVisibility(t.id(), t.playerId(), makePublic), t.ref(),
                r -> success(player, text("").append(serverName(t.ref())).append(text(makePublic
                        ? " est public : tout le monde peut y entrer."
                        : " est privé : seuls tes invités peuvent y entrer.")))));
    }

    private void handleDisplay(Player player, String[] args) {
        if (args.length != 3 || !("on".equalsIgnoreCase(args[2]) || "off".equalsIgnoreCase(args[2]))) {
            usage(player, "/mcs display <serveur> on|off", "/mcs display ");
            return;
        }
        boolean enabled = "on".equalsIgnoreCase(args[2]);
        withServer(player, args[1], t -> afterApi(player,
                apiClient.setDisplay(t.id(), t.playerId(), enabled), t.ref(),
                r -> success(player, text("Rôles réseau sur ").append(serverName(t.ref())).append(text(enabled
                        ? " : affichés (préfixe [Admin], [VIP]... à l'arrivée des joueurs)."
                        : " : masqués, les équipes du serveur sont libres.")))));
    }

    // ============================================================ hôtes (admins) ====

    // ============================================================ sauvegardes ====
    //
    // Types : quotidienne, hebdomadaire, mensuelle (automatiques), manuelle,
    // permanente. Chacun : un nombre max (au-delà, la plus ancienne part) et une
    // durée de vie. L'API décide ; ici on affiche et on transmet.

    private static final String[] KIND_ORDER = {"DAILY", "WEEKLY", "MONTHLY", "MANUAL", "PERMANENT"};

    /** "quotidienne", "hebdo", "perma"... -> nom de l'API ; null si inconnu */
    static String backupKind(String s) {
        return switch (s == null ? "" : s.toLowerCase(Locale.ROOT)) {
            case "quotidienne", "quotidien", "jour", "jours", "daily", "day" -> "DAILY";
            case "hebdomadaire", "hebdo", "semaine", "weekly", "week" -> "WEEKLY";
            case "mensuelle", "mensuel", "mois", "monthly", "month" -> "MONTHLY";
            case "manuelle", "manuel", "manual" -> "MANUAL";
            case "permanente", "permanent", "perma" -> "PERMANENT";
            default -> null;
        };
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "DAILY" -> "Quotidienne";
            case "WEEKLY" -> "Hebdomadaire";
            case "MONTHLY" -> "Mensuelle";
            case "MANUAL" -> "Manuelle";
            case "PERMANENT" -> "Permanente";
            default -> kind;
        };
    }

    private static String kindShort(String kind) {
        return switch (kind) {
            case "DAILY" -> "jour";
            case "WEEKLY" -> "semaine";
            case "MONTHLY" -> "mois";
            case "MANUAL" -> "manuelle";
            case "PERMANENT" -> "permanente";
            default -> kind.toLowerCase(Locale.ROOT);
        };
    }

    private static final Pattern DURATION_PATTERN =
            Pattern.compile("^(\\d{1,5})(h|heures?|j|jours?|d|s|sem|semaines?|w|m|mois)?$");

    /**
     * "3", "3j", "2sem", "1mois", "24h" -> valeur dans l'unité du type (jours ;
     * heures pour les permanentes). Sans unité : celle du type. null si invalide.
     */
    static Integer parseDuration(String raw, boolean hours) {
        Matcher m = DURATION_PATTERN.matcher(raw.toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return null;
        }
        long n = Long.parseLong(m.group(1));
        String u = m.group(2) == null ? "" : m.group(2);
        if (u.isEmpty()) {
            return (int) n;
        }
        long h;
        if (u.startsWith("h")) {
            h = n;
        } else if (u.startsWith("j") || u.equals("d")) {
            h = n * 24;
        } else if (u.startsWith("s") || u.equals("w")) {
            h = n * 24 * 7;
        } else {
            h = n * 24 * 30;
        }
        if (hours) {
            return (int) h;
        }
        return h % 24 == 0 ? (int) (h / 24) : null;
    }

    /** "3" ou "off" (0) ; null si invalide */
    private static Integer parseMax(String raw) {
        if (raw.equalsIgnoreCase("off") || raw.equalsIgnoreCase("non")) {
            return 0;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String days(int d) {
        return d + (d > 1 ? " jours" : " jour");
    }

    /** "3 max · 3 jours", "coupée", "1 max · 24 h après la suppression du serveur" */
    private static String ruleText(String kind, JsonNode rule) {
        int max = rule.path("max").asInt(0);
        int dur = rule.path("duration").asInt(0);
        if (max <= 0) {
            return "coupée";
        }
        if ("PERMANENT".equals(kind)) {
            return max + " max · " + (dur <= 0 ? "supprimée avec le serveur"
                    : "gardée " + (dur % 24 == 0 ? days(dur / 24) : dur + " h") + " après la suppression du serveur");
        }
        return max + " max · " + days(dur);
    }

    /** "dans 2 j 5 h" jusqu'à une date (heure du VPS) */
    private static String remaining(String until) {
        try {
            java.time.Duration d = java.time.Duration.between(java.time.LocalDateTime.now(),
                    java.time.LocalDateTime.parse(until));
            long min = d.toMinutes();
            if (min <= 0) {
                return "bientôt";
            }
            if (min < 60) {
                return "dans " + min + " min";
            }
            if (min < 24 * 60) {
                return "dans " + (min / 60) + " h";
            }
            return "dans " + (min / (24 * 60)) + " j " + ((min / 60) % 24) + " h";
        } catch (Exception e) {
            return "?";
        }
    }

    private void backupHelp(Player player) {
        usage(player, "/mcs backup <serveur>", "/mcs backup ");
        send(player, Component.text("Aussi : ", NamedTextColor.GRAY)
                .append(Component.text("/mcs backups <serveur>", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.suggestCommand("/mcs backups ")))
                .append(Component.text(" (historique)  ·  ", NamedTextColor.GRAY))
                .append(Component.text("/mcs backup keep <serveur> [n°]", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.suggestCommand("/mcs backup keep ")))
                .append(Component.text(" (permanente)  ·  ", NamedTextColor.GRAY))
                .append(Component.text("/mcs backup settings <serveur>", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.suggestCommand("/mcs backup settings ")))
                .append(Component.text(" (réglages)", NamedTextColor.GRAY)));
    }

    private void handleBackup(Player player, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("defaults")) {
            handleBackupScope(player, "network", args, 2);
            return;
        }
        if (sub.equals("limits") || sub.equals("role")) {
            if (args.length < 3) {
                usage(player, "/mcs backup limits <rôle> [type max durée | reset]", "/mcs backup limits ");
                return;
            }
            handleBackupScope(player, args[2].toLowerCase(Locale.ROOT), args, 3);
            return;
        }
        if (args.length >= 3) {
            switch (sub) {
                case "keep", "unkeep" -> {
                    handleBackupKeep(player, args, sub.equals("keep"));
                    return;
                }
                case "settings" -> {
                    withServer(player, args[2], t -> afterApi(player, apiClient.listBackups(t.id(), t.playerId()),
                            t.ref(), r -> sendBackupSettings(player, t, r)));
                    return;
                }
                case "set" -> {
                    handleBackupSet(player, args);
                    return;
                }
                default -> {
                }
            }
        }
        if (args.length != 2) {
            backupHelp(player);
            return;
        }
        withServer(player, args[1], t -> {
            if (!t.can("POWER")) {
                failure(player, text("Tu n'as pas le droit de sauvegarder ce serveur."));
                return;
            }
            runBackup(player, t, false);
        });
    }

    /** Sauvegarde maintenant ; permanente : propriétaire et admins (vérifié par l'API) */
    private void runBackup(Player player, Target t, boolean permanent) {
        pending(player, text((permanent ? "Sauvegarde permanente de " : "Sauvegarde de "))
                .append(Component.text(t.ref(), NamedTextColor.WHITE))
                .append(text("… (le monde est figé quelques secondes)")));
        afterApi(player, apiClient.backupNow(t.id(), t.playerId(), permanent), t.ref(), r -> {
            String status = r.path("status").asText("");
            if ("SUCCESS".equals(status)) {
                boolean onMachine = "LOCAL".equals(r.path("location").asText());
                success(player, text("").append(serverName(t.ref())).append(text(permanent
                        ? " est sauvegardé (n°" + r.path("id").asLong() + ", permanente)."
                        : " est sauvegardé (n°" + r.path("id").asLong() + (onMachine ? ", sur la machine)." : ")."))));
                if (r.path("fallback").asBoolean(false)) {
                    notice(player, text("Plus de place sur la machine (disque du serveur plein) : sauvegardée au central."));
                }
                player.sendMessage(Component.text("   " + sizeOrDash(r.path("addedMb")) + " nouveaux envoyés  ·  "
                        + sizeOrDash(r.path("totalMb")) + " au total", NamedTextColor.GRAY));
                sendRotation(player, r, -1);
            } else if ("RUNNING".equals(status)) {
                notice(player, text("La sauvegarde de " + t.ref() + " continue en arrière-plan."));
            } else {
                failure(player, text("Sauvegarde échouée : " + r.path("message").asText("erreur inconnue")));
            }
            player.sendMessage(buttons(button("Historique", NamedTextColor.AQUA, "/mcs backups " + t.ref(),
                    "Sauvegardes de " + t.ref())));
        });
    }

    /** Ce que les limites ont changé : permanentes rétrogradées, sauvegardes supprimées */
    private static void sendRotation(Player player, JsonNode r, long except) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        int maxPerm = r.path("maxPermanent").asInt(-1);
        for (JsonNode id : r.path("demoted")) {
            parts.add("n°" + id.asLong() + " n'est plus permanente" + (maxPerm >= 0 ? " (" + maxPerm + " max)" : ""));
        }
        for (JsonNode id : r.path("expired")) {
            parts.add(id.asLong() == except ? "elle expire (plus aucune règle ne la garde)" : "n°" + id.asLong() + " supprimée");
        }
        if (!parts.isEmpty()) {
            notice(player, text("Limites : " + String.join(" · ", parts) + "."));
        }
    }

    private static String sizeOrDash(JsonNode mb) {
        return mb == null || mb.isNull() || mb.isMissingNode() ? "?" : size(mb.asLong());
    }

    /** /mcs backup keep <serveur> [n°]  ·  /mcs backup unkeep <serveur> <n°> */
    private void handleBackupKeep(Player player, String[] args, boolean keep) {
        if (args.length > 4 || (!keep && args.length != 4)) {
            usage(player, keep ? "/mcs backup keep <serveur> [n°]" : "/mcs backup unkeep <serveur> <n°>",
                    keep ? "/mcs backup keep " : "/mcs backup unkeep ");
            return;
        }
        Long id = null;
        if (args.length == 4) {
            try {
                id = Long.parseLong(args[3].replace("n°", "").replace("#", ""));
            } catch (NumberFormatException e) {
                failure(player, text("Numéro de sauvegarde invalide : " + args[3] + " (voir /mcs backups)."));
                return;
            }
        }
        Long backupId = id;
        withServer(player, args[2], t -> {
            if (backupId == null) {
                runBackup(player, t, true);
                return;
            }
            afterApi(player, apiClient.setBackupPermanent(t.id(), backupId, t.playerId(), keep), t.ref(), r -> {
                success(player, text("Sauvegarde n°" + backupId + " de ").append(serverName(t.ref()))
                        .append(text(keep ? " : permanente, gardée tant que le serveur existe."
                                : " : n'est plus permanente, elle suit les autres règles.")));
                sendRotation(player, r, backupId);
                player.sendMessage(buttons(button("Historique", NamedTextColor.AQUA, "/mcs backups " + t.ref(),
                        "Sauvegardes de " + t.ref())));
            });
        });
    }

    private void handleBackups(Player player, String[] args) {
        if (args.length != 2) {
            usage(player, "/mcs backups <serveur>", "/mcs backups ");
            return;
        }
        withServer(player, args[1], t -> afterApi(player, apiClient.listBackups(t.id(), t.playerId()), t.ref(),
                r -> sendBackups(player, t, r)));
    }

    private void sendBackups(Player player, Target t, JsonNode r) {
        String ref = t.ref();
        boolean canConfigure = r.path("canConfigure").asBoolean(false);
        JsonNode rules = r.path("rules");
        player.sendMessage(Component.empty());
        player.sendMessage(header("Sauvegardes de " + ref));
        if (!r.path("enabled").asBoolean(false)) {
            player.sendMessage(Component.text(" Les sauvegardes ne sont pas encore configurées sur ce réseau.",
                    NamedTextColor.GOLD));
        }
        // Règles en une ligne, détail au survol
        StringBuilder auto = new StringBuilder();
        StringBuilder detail = new StringBuilder();
        for (String k : KIND_ORDER) {
            JsonNode rule = rules.path(k);
            detail.append(kindLabel(k)).append(" : ").append(ruleText(k, rule)).append('\n');
            if (rule.path("max").asInt(0) > 0 && !k.equals("MANUAL") && !k.equals("PERMANENT")) {
                auto.append(auto.length() == 0 ? "" : ", ").append("1 par ").append(kindShort(k))
                        .append(" (").append(days(rule.path("duration").asInt())).append(")");
            }
        }
        detail.append(r.path("customized").asBoolean(false) ? "Réglages propres à ce serveur" : "Réglages du réseau");
        String park = switch (r.path("parkState").asText("")) {
            case "CLEAN" -> "rangé au central ✔";
            case "COLD" -> "rangé au central (il démarrera sur la machine qui a de la place)";
            case "LOST" -> "machine hors ligne : repartira de sa dernière sauvegarde";
            default -> "en marche, ou rangé 2 min après son arrêt";
        };
        player.sendMessage(Component.text(" État : ", NamedTextColor.GRAY).append(Component.text(park, NamedTextColor.WHITE))
                .hoverEvent(HoverEvent.showText(Component.text("À chaque arrêt, le serveur est rangé au central :\n"
                        + "il peut ensuite démarrer sur n'importe quelle machine qui a de la place.", NamedTextColor.GRAY))));
        player.sendMessage(Component.text(" Automatique : ", NamedTextColor.GRAY)
                .append(Component.text(auto.length() == 0 ? "coupée" : auto.toString(), NamedTextColor.WHITE))
                .hoverEvent(HoverEvent.showText(Component.text(detail.toString(), NamedTextColor.GRAY))));
        JsonNode local = r.path("local");
        if (local.path("enabled").asBoolean(false)) {
            JsonNode lr = local.path("rules");
            player.sendMessage(Component.text(" Sur la machine : ", NamedTextColor.GRAY)
                    .append(Component.text((lr.path("DAILY").path("max").asInt(0) > 0
                            ? "1 par jour (" + days(lr.path("DAILY").path("duration").asInt()) + ")" : "quotidienne coupée")
                            + ", manuelles " + ruleText("MANUAL", lr.path("MANUAL")), NamedTextColor.WHITE))
                    .hoverEvent(HoverEvent.showText(Component.text(size(local.path("repoMb").asLong(0))
                            + " utilisés, comptés dans le disque du serveur (" + size(local.path("quotaMb").asLong(0)) + ")\n"
                            + "Plus rapide à restaurer ; les quotidiennes sont aussi au central.", NamedTextColor.GRAY))));
        }

        JsonNode list = r.path("backups");
        if (list.size() == 0) {
            player.sendMessage(Component.text(" Aucune sauvegarde pour l'instant.", NamedTextColor.GRAY));
        }
        for (JsonNode b : list) {
            String status = b.path("status").asText("?");
            long id = b.path("id").asLong();
            String ago = b.hasNonNull("createdAt") ? uptime(b.get("createdAt").asText()) : null;
            Component icon = switch (status) {
                case "SUCCESS" -> Component.text("✔", NamedTextColor.GREEN);
                case "RUNNING" -> Component.text("◐", NamedTextColor.YELLOW);
                case "EXPIRED" -> Component.text("⌛", NamedTextColor.DARK_GRAY);
                case "NO_SPACE" -> Component.text("⚠", NamedTextColor.GOLD);
                default -> Component.text("✖", NamedTextColor.RED);
            };
            boolean permanent = false;
            StringBuilder kinds = new StringBuilder();
            for (JsonNode k : b.path("kinds")) {
                permanent |= "PERMANENT".equals(k.asText());
                kinds.append(kinds.length() == 0 ? "" : "+").append(kindShort(k.asText()));
            }
            if (permanent) {
                // Une permanente ne compte que comme permanente (elle ne prend la place d'aucune autre)
                kinds = new StringBuilder("permanente");
            }
            boolean onMachine = "LOCAL".equals(b.path("location").asText());
            Component line = Component.text(" ").append(icon)
                    .append(Component.text(" n°" + id, NamedTextColor.DARK_AQUA))
                    .append(Component.text("  il y a " + (ago == null ? "?" : ago), NamedTextColor.WHITE))
                    .append(Component.text("  " + (kinds.length() == 0 ? "auto" : kinds), NamedTextColor.GRAY))
                    .append(onMachine ? Component.text(" ⌂", NamedTextColor.DARK_GREEN) : Component.empty());
            StringBuilder hover = new StringBuilder("Sauvegarde n°" + id + (onMachine ? " · sur la machine" : " · au central"));
            if (b.hasNonNull("requestedBy")) {
                hover.append("\nDemandée par ").append(b.get("requestedBy").asText());
            }
            switch (status) {
                case "SUCCESS" -> {
                    hover.append("\n+").append(sizeOrDash(b.path("addedMb"))).append(" envoyés, ")
                            .append(sizeOrDash(b.path("totalMb"))).append(" au total");
                    String until = b.hasNonNull("expiresAt") ? remaining(b.get("expiresAt").asText()) : null;
                    line = line.append(Component.text("  " + (until != null ? "expire " + until
                            : permanent ? "gardée" : "la plus récente"), NamedTextColor.DARK_GRAY));
                }
                case "EXPIRED" -> {
                    hover.append("\nExpirée : supprimée au prochain nettoyage du serveur de sauvegarde");
                    line = line.append(Component.text("  expirée", NamedTextColor.DARK_GRAY));
                }
                case "FAILED", "NO_SPACE" -> hover.append("\n").append(b.path("message").asText("erreur"));
                default -> {
                }
            }
            line = line.hoverEvent(HoverEvent.showText(Component.text(hover.toString(),
                    "FAILED".equals(status) ? NamedTextColor.RED : NamedTextColor.GRAY)));
            if (r.path("canRestore").asBoolean(false) && "SUCCESS".equals(status)) {
                line = line.append(text(" ")).append(Component.text("↺", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/mcs restore " + ref + " " + id))
                        .hoverEvent(HoverEvent.showText(Component.text("Restaurer cette sauvegarde\n(une confirmation est demandée)",
                                NamedTextColor.GRAY))));
            }
            if (canConfigure && "SUCCESS".equals(status) && !onMachine) {
                line = line.append(text(" ")).append(permanent
                        ? Component.text("★", NamedTextColor.GOLD)
                                .clickEvent(ClickEvent.runCommand("/mcs backup unkeep " + ref + " " + id))
                                .hoverEvent(HoverEvent.showText(Component.text(
                                        "Permanente. Cliquer pour ne plus la garder", NamedTextColor.GRAY)))
                        : Component.text("☆", NamedTextColor.DARK_GRAY)
                                .clickEvent(ClickEvent.runCommand("/mcs backup keep " + ref + " " + id))
                                .hoverEvent(HoverEvent.showText(Component.text(
                                        "Garder (permanente, tant que le serveur existe)", NamedTextColor.GRAY))));
            }
            player.sendMessage(line);
        }
        java.util.List<Component> actions = new java.util.ArrayList<>();
        if (t.can("POWER")) {
            actions.add(button("Sauvegarder", NamedTextColor.GREEN, "/mcs backup " + ref,
                    "Sauvegarde manuelle (" + ruleText("MANUAL", rules.path("MANUAL")) + ")"));
        }
        if (canConfigure) {
            actions.add(button("Permanente", NamedTextColor.GOLD, "/mcs backup keep " + ref,
                    "Sauvegarde gardée tant que le serveur existe\n(" + ruleText("PERMANENT", rules.path("PERMANENT")) + ")"));
        }
        actions.add(button("Réglages", NamedTextColor.AQUA, "/mcs backup settings " + ref,
                "Combien de sauvegardes et combien de temps"));
        player.sendMessage(buttons(actions.toArray(new Component[0])));
    }

    /** Lignes de réglages : une par type présent, avec un bouton pour la modifier */
    private void sendRuleLines(Player player, JsonNode rules, JsonNode caps, String capLabel, String setCmd) {
        for (String k : KIND_ORDER) {
            if (!rules.has(k)) {
                continue;
            }
            JsonNode rule = rules.path(k);
            Component line = Component.text("  " + kindLabel(k) + " : ", NamedTextColor.GRAY)
                    .append(Component.text(ruleText(k, rule), NamedTextColor.WHITE));
            if (setCmd != null) {
                String unit = "PERMANENT".equals(k) ? "h" : "j";
                JsonNode cap = caps == null ? rule : caps.path(k);
                String hover = (caps == null ? "" : capLabel + " : " + ruleText(k, cap) + "\n")
                        + "Ex. : " + setCmd + " " + kindLabel(k).toLowerCase(Locale.ROOT) + " "
                        + Math.max(1, cap.path("max").asInt(1)) + " " + cap.path("duration").asInt(1) + unit
                        + "\n0 = coupée · durée en " + ("h".equals(unit) ? "heures (ou 2j)" : "jours (ou 2sem)");
                line = line.append(text(" ")).append(suggestButton("Modifier", NamedTextColor.YELLOW,
                        setCmd + " " + kindLabel(k).toLowerCase(Locale.ROOT) + " ", hover));
            }
            player.sendMessage(line);
        }
    }

    private void sendBackupSettings(Player player, Target t, JsonNode r) {
        String ref = t.ref();
        boolean admin = r.path("canConfigure").asBoolean(false);
        String rank = r.path("rank").asText("default");
        player.sendMessage(Component.empty());
        player.sendMessage(header("Sauvegardes de " + ref));
        player.sendMessage(Component.text(" Max : au-delà, la plus ancienne est supprimée. Durée : combien de temps "
                + "chacune reste.", NamedTextColor.GRAY));
        player.sendMessage(Component.text(" Réglages du rôle " + rank, NamedTextColor.GOLD)
                .append(Component.text(r.path("customized").asBoolean(false) ? "  · exception posée par un admin" : "",
                        NamedTextColor.DARK_GRAY)));
        sendRuleLines(player, r.path("rules"), null, null, admin ? "/mcs backup set " + ref : null);
        player.sendMessage(Component.text(" Toutes les sauvegardes sont sur le serveur de sauvegarde MCS. "
                + "Les réglages suivent le rôle du propriétaire.", NamedTextColor.DARK_GRAY));
        java.util.List<Component> actions = new java.util.ArrayList<>();
        actions.add(button("Historique", NamedTextColor.AQUA, "/mcs backups " + ref, "Sauvegardes de " + ref));
        if (admin && r.path("customized").asBoolean(false)) {
            actions.add(button("Réglages du rôle", NamedTextColor.AQUA, "/mcs backup set " + ref + " reset",
                    "Retirer l'exception : le serveur suit le rôle de son propriétaire"));
        }
        player.sendMessage(buttons(actions.toArray(new Component[0])));
    }

    /** Lit "<type> <max> [durée]" à partir de args[from] ; null (et message) si invalide */
    private record RuleArgs(String kind, Integer max, Integer duration) {}

    private static RuleArgs parseRuleArgs(Player player, String[] args, int from, String example) {
        int rest = args.length - from;
        if (rest < 2 || rest > 3) {
            failure(player, text("Ex. : " + example));
            return null;
        }
        String kind = backupKind(args[from]);
        if (kind == null) {
            failure(player, text("Type inconnu : " + args[from] + " (quotidienne, hebdomadaire, mensuelle, manuelle, permanente)."));
            return null;
        }
        Integer max = parseMax(args[from + 1]);
        if (max == null) {
            failure(player, text("Nombre max invalide : " + args[from + 1] + " (0 = coupée)."));
            return null;
        }
        Integer duration = null;
        if (rest == 3) {
            duration = parseDuration(args[from + 2], "PERMANENT".equals(kind));
            if (duration == null) {
                failure(player, text("Durée invalide : " + args[from + 2] + " (ex. 3, 3j, 2sem"
                        + ("PERMANENT".equals(kind) ? ", 24h" : "") + ")."));
                return null;
            }
        }
        return new RuleArgs(kind, max, duration);
    }

    /** Admins : /mcs backup set <serveur> <type> <max> [durée]  ·  /mcs backup set <serveur> reset (exception) */
    private void handleBackupSet(Player player, String[] args) {
        boolean local = false;
        int from = 3;
        boolean reset = args.length == from + 1 && args[from].equalsIgnoreCase("reset");
        if (!reset && (args.length < from + 2 || args.length > from + 3)) {
            usage(player, "/mcs backup set <serveur> <type> <max> [durée]", "/mcs backup set ");
            send(player, Component.text("Admins : exception pour un serveur. Ex. : /mcs backup set survie quotidienne 5 7j  ·  "
                    + "0 = coupée  ·  reset = réglages du rôle. Pour tout un rôle : /mcs backup limits <rôle>",
                    NamedTextColor.GRAY));
            return;
        }
        RuleArgs rule = reset ? new RuleArgs(null, null, null)
                : parseRuleArgs(player, args, from, "/mcs backup set survie quotidienne 5 7j");
        if (rule == null) {
            return;
        }
        String where = " (exception)";
        withServer(player, args[2], t -> afterApi(player,
                apiClient.setBackupRule(t.id(), t.playerId(), rule.kind(), rule.max(), rule.duration(), reset, local),
                t.ref(), r -> {
                    if (reset) {
                        success(player, text("Sauvegardes de ").append(serverName(t.ref()))
                                .append(text(where + " : réglages par défaut.")));
                    } else {
                        JsonNode now = r.path(local ? "localRules" : "rules").path(rule.kind());
                        success(player, text(kindLabel(rule.kind()) + " de ").append(serverName(t.ref()))
                                .append(text(where + " : " + ruleText(rule.kind(), now) + ".")));
                    }
                    player.sendMessage(buttons(button("Réglages", NamedTextColor.AQUA, "/mcs backup settings " + t.ref(),
                            "Voir tous les réglages")));
                }));
    }

    /**
     * Admins : /mcs backup defaults [type max durée]              défauts du réseau
     *          /mcs backup role <rôle> [type max durée | reset]   réglages d'un rôle (alias : limits)
     */
    private void handleBackupScope(Player player, String scope, String[] args, int from) {
        int rest = args.length - from;
        String cmd = switch (scope) {
            case "network" -> "/mcs backup defaults";
            case "local" -> "/mcs backup defaults local";
            case "min" -> "/mcs backup minimum";
            default -> "/mcs backup limits " + scope;
        };
        boolean isRank = !scope.equals("network") && !scope.equals("local") && !scope.equals("min");
        boolean reset = rest == 1 && args[from].equalsIgnoreCase("reset") && (isRank || scope.equals("min"));
        RuleArgs rule = new RuleArgs(null, null, null);
        if (rest != 0 && !reset) {
            rule = parseRuleArgs(player, args, from, cmd + " quotidienne 3 3j");
            if (rule == null) {
                return;
            }
        }
        boolean change = rest != 0;
        RuleArgs fRule = rule;
        String title = switch (scope) {
            case "network" -> "Sauvegardes : défauts du réseau";
            case "local" -> "Sauvegardes : minimum des machines";
            case "min" -> "Sauvegardes : minimum au central";
            default -> "Sauvegardes du rôle " + scope;
        };
        String explain = switch (scope) {
            case "network" -> " Pour les rôles qui n'ont pas leurs propres réglages.";
            case "local" -> " Ce que chaque hôte offre au moins sur sa machine (et son réglage par défaut).";
            case "min" -> " Toujours gardé au central : un propriétaire ne peut pas descendre en dessous.";
            default -> " Réglages des serveurs dont le propriétaire a le rôle " + scope + " (sans rien : défauts du réseau).";
        };
        apiClient.getPlayerByUuid(player.getUniqueId())
                .thenCompose(info -> {
                    long id = info.get("id").asLong();
                    return change ? apiClient.setBackupScopeRule(id, scope, fRule.kind(), fRule.max(), fRule.duration(), reset)
                            : apiClient.getBackupScope(id, scope);
                })
                .whenComplete((r, error) -> run(() -> {
                    if (error != null) {
                        reportError(player, error, null);
                        return;
                    }
                    if (change) {
                        success(player, text(reset ? title + " : remis à zéro."
                                : title + " : " + kindLabel(fRule.kind()).toLowerCase(Locale.ROOT) + " = "
                                + ruleText(fRule.kind(), r.path("rules").path(fRule.kind())) + "."));
                    }
                    player.sendMessage(Component.empty());
                    player.sendMessage(header(title));
                    player.sendMessage(Component.text(explain, NamedTextColor.GRAY));
                    sendRuleLines(player, r.path("rules"), null, null, cmd);
                }));
    }

    // ============================================================ déplacements ====

    /** Message MCS reçu (serveur déplacé, machine hors ligne...) */
    public static void notifyPlayer(Player player, String message) {
        player.sendMessage(PREFIX.append(Component.text("✉ ", NamedTextColor.GOLD))
                .append(Component.text(message, NamedTextColor.YELLOW)));
    }

    /** /mcs regions : régions des machines */
    private void handleRegions(Player player) {
        apiClient.getRegions().whenComplete((r, error) -> run(() -> {
            if (error != null) {
                reportError(player, error, null);
                return;
            }
            player.sendMessage(Component.empty());
            player.sendMessage(header("Régions"));
            for (JsonNode reg : r.path("regions")) {
                String name = reg.path("region").asText("?");
                int online = reg.path("online").asInt(0);
                player.sendMessage(Component.text(" " + name, NamedTextColor.WHITE, TextDecoration.BOLD)
                        .append(Component.text("  " + online + "/" + reg.path("machines").asInt(0) + " machine(s) en ligne",
                                online > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)));
            }
            player.sendMessage(Component.text(" Changer la région d'un serveur : ", NamedTextColor.GRAY)
                    .append(Component.text("/mcs migrate <serveur> <région>", NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.suggestCommand("/mcs migrate "))));
        }));
    }

    /**
     * /mcs migrate <serveur> [région | machine <n°>] [confirm]
     * /mcs migrate <serveur> backup [n°] [région | machine <n°>] [confirm]   (machine hors ligne)
     */
    private void handleMigrate(Player player, String[] args) {
        if (args.length < 2) {
            usage(player, "/mcs migrate <serveur> [région]", "/mcs migrate ");
            send(player, Component.text("Régions : /mcs regions  ·  machine hors ligne : /mcs migrate <serveur> backup",
                    NamedTextColor.GRAY));
            return;
        }
        java.util.List<String> rest = new java.util.ArrayList<>(java.util.Arrays.asList(args).subList(2, args.length));
        boolean confirmed = !rest.isEmpty() && "confirm".equalsIgnoreCase(rest.get(rest.size() - 1));
        if (confirmed) {
            rest.remove(rest.size() - 1);
        }
        boolean fromBackup = false;
        Long backupId = null;
        Long machine = null;
        String region = null;
        int i = 0;
        if (i < rest.size() && rest.get(i).equalsIgnoreCase("backup")) {
            fromBackup = true;
            i++;
            if (i < rest.size() && rest.get(i).replace("n°", "").replace("#", "").matches("\\d+")) {
                backupId = Long.parseLong(rest.get(i).replace("n°", "").replace("#", ""));
                i++;
            }
        }
        if (i < rest.size() && rest.get(i).equalsIgnoreCase("machine")) {
            if (i + 1 >= rest.size() || !rest.get(i + 1).replace("#", "").matches("\\d+")) {
                failure(player, text("Numéro de machine attendu (voir /mcs host list)."));
                return;
            }
            machine = Long.parseLong(rest.get(i + 1).replace("#", ""));
            i += 2;
        } else if (i < rest.size()) {
            region = rest.get(i).toLowerCase(Locale.ROOT);
            i++;
        }
        if (i != rest.size()) {
            usage(player, "/mcs migrate <serveur> [backup [n°]] [région | machine <n°>]", "/mcs migrate ");
            return;
        }
        String where = machine != null ? "la machine " + machine : region != null ? "la région " + region : "une autre machine";
        String cmd = "/mcs migrate " + String.join(" ", java.util.Arrays.asList(args).subList(1, args.length))
                .replaceFirst("(?i)\\s+confirm$", "");
        boolean fFromBackup = fromBackup;
        Long fBackupId = backupId;
        Long fMachine = machine;
        String fRegion = region;
        withServer(player, args[1], t -> {
            if (!confirmed) {
                notice(player, text("Déplacer ").append(serverName(t.ref())).append(text(" vers " + where + " ? "
                        + (fFromBackup
                        ? "Il repartira de sa " + (fBackupId == null ? "dernière sauvegarde" : "sauvegarde n°" + fBackupId)
                          + " au central : ce qui a été fait après est perdu."
                        : "Il sera arrêté quelques minutes (joueurs renvoyés au lobby), sauvegardé, puis relancé "
                          + "sur sa nouvelle machine. Rien n'est perdu."))));
                player.sendMessage(buttons(button("Confirmer", NamedTextColor.GREEN, cmd + " confirm", "Déplacer " + t.ref())));
                return;
            }
            pending(player, text("Déplacement de ").append(Component.text(t.ref(), NamedTextColor.WHITE))
                    .append(text(" vers " + where + "… (quelques minutes)")));
            afterApi(player, apiClient.migrateServer(t.id(), t.playerId(), fRegion, fMachine, fFromBackup, fBackupId), t.ref(), r -> {
                if ("RUNNING".equals(r.path("status").asText())) {
                    notice(player, text("Le déplacement de " + t.ref() + " continue en arrière-plan (" + r.path("target").asText("?") + ")."));
                    return;
                }
                success(player, text("").append(serverName(t.ref())).append(text(" est maintenant sur la "
                        + r.path("target").asText("?") + (r.path("running").asBoolean(false) ? ", relancé." : "."))));
                player.sendMessage(buttons(r.path("running").asBoolean(false) ? joinButton(t.ref()) : startButton(t.ref()),
                        infoButton(t.ref())));
            });
        });
    }

    /**
     * Admins : /mcs machine region <n°> <région>
     *          /mcs machine backup <n°>                       sauvegarde tous ses serveurs au central
     *          /mcs machine evacuate <n°> [machine] [backup]  déplace tous ses serveurs
     */
    private void handleMachine(Player player, String[] args) {
        if (args.length < 3 || !args[2].replace("#", "").matches("\\d+")) {
            usage(player, "/mcs machine <region|backup|evacuate> <n°> ...", "/mcs machine ");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        long machine = Long.parseLong(args[2].replace("#", ""));
        String region = null;
        Long target = null;
        boolean fromBackup = false;
        switch (action) {
            case "region" -> {
                if (args.length != 4) {
                    usage(player, "/mcs machine region <n°> <région>", "/mcs machine region ");
                    return;
                }
                region = args[3].toLowerCase(Locale.ROOT);
            }
            case "backup" -> {
                if (args.length != 3) {
                    usage(player, "/mcs machine backup <n°>", "/mcs machine backup ");
                    return;
                }
            }
            case "evacuate" -> {
                for (int i = 3; i < args.length; i++) {
                    if (args[i].equalsIgnoreCase("backup")) {
                        fromBackup = true;
                    } else if (args[i].replace("#", "").matches("\\d+")) {
                        target = Long.parseLong(args[i].replace("#", ""));
                    } else {
                        usage(player, "/mcs machine evacuate <n°> [machine] [backup]", "/mcs machine evacuate ");
                        return;
                    }
                }
            }
            default -> {
                usage(player, "/mcs machine <region|backup|evacuate> <n°> ...", "/mcs machine ");
                return;
            }
        }
        String fRegion = region;
        Long fTarget = target;
        boolean fFromBackup = fromBackup;
        apiClient.getPlayerByUuid(player.getUniqueId())
                .thenCompose(info -> apiClient.machineAction(machine, action, info.get("id").asLong(), fRegion, fTarget, fFromBackup))
                .whenComplete((r, error) -> run(() -> {
                    if (error != null) {
                        reportError(player, error, null);
                        return;
                    }
                    switch (action) {
                        case "region" -> success(player, text("Machine " + machine + " : région " + r.path("region").asText() + "."));
                        case "backup" -> success(player, text("Machine " + machine + " : sauvegarde de "
                                + r.path("servers").asInt() + " serveur(s) lancée. Tu recevras le résultat ici."));
                        default -> success(player, text(r.path("servers").asInt() == 0 ? "Machine " + machine + " : aucun serveur."
                                : "Machine " + machine + " : déplacement de " + r.path("servers").asInt()
                                + " serveur(s) lancé (un à la fois). Les propriétaires sont prévenus ; tu recevras le résultat ici."));
                    }
                }));
    }

    // ============================================================ restauration ====

    private static Long parseBackupNumber(Player player, String raw) {
        try {
            return Long.parseLong(raw.replace("n°", "").replace("#", ""));
        } catch (NumberFormatException e) {
            failure(player, text("Numéro de sauvegarde invalide : " + raw + " (voir /mcs backups)."));
            return null;
        }
    }

    /**
     * /mcs restore <serveur> <n°> [confirm]         remplace le monde par la sauvegarde
     * /mcs restore deleted                          sauvegardes des serveurs supprimés
     * /mcs restore deleted <n°> [nom] [confirm]     recrée un serveur supprimé
     * Propriétaire et admins seulement (vérifié par l'API).
     */
    private void handleRestore(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("deleted")) {
            handleRestoreDeleted(player, args);
            return;
        }
        if (args.length < 3 || args.length > 4 || (args.length == 4 && !"confirm".equalsIgnoreCase(args[3]))) {
            usage(player, "/mcs restore <serveur> <n°>", "/mcs restore ");
            send(player, Component.text("Numéros : /mcs backups <serveur>  ·  serveur supprimé : /mcs restore deleted",
                    NamedTextColor.GRAY));
            return;
        }
        Long backupId = parseBackupNumber(player, args[2]);
        if (backupId == null) {
            return;
        }
        boolean confirmed = args.length == 4;
        withServer(player, args[1], t -> {
            if (!confirmed) {
                notice(player, text("Restaurer la sauvegarde n°" + backupId + " de ").append(serverName(t.ref()))
                        .append(text(" ? Le monde actuel sera remplacé, sans retour en arrière. "
                                + "S'il tourne, le serveur est arrêté (joueurs renvoyés au lobby) puis relancé.")));
                player.sendMessage(buttons(
                        button("Confirmer", NamedTextColor.RED, "/mcs restore " + t.ref() + " " + backupId + " confirm",
                                "Remplacer le monde de " + t.ref() + " par la sauvegarde n°" + backupId),
                        button("Historique", NamedTextColor.AQUA, "/mcs backups " + t.ref(), "Sauvegardes de " + t.ref())));
                return;
            }
            pending(player, text("Restauration de ").append(Component.text(t.ref(), NamedTextColor.WHITE))
                    .append(text(" depuis la n°" + backupId + "… (quelques minutes)")));
            afterApi(player, apiClient.restoreBackup(t.id(), t.playerId(), backupId), t.ref(), r -> {
                if ("RUNNING".equals(r.path("status").asText())) {
                    notice(player, text("La restauration de " + t.ref() + " continue en arrière-plan."));
                    return;
                }
                if (r.path("parked").asBoolean(false)) {
                    success(player, text("").append(serverName(t.ref())).append(text(" est rangé au central : il "
                            + "démarrera depuis la sauvegarde n°" + backupId + ".")));
                    player.sendMessage(buttons(startButton(t.ref()), infoButton(t.ref())));
                    return;
                }
                success(player, text("").append(serverName(t.ref()))
                        .append(text(" est restauré (sauvegarde n°" + backupId + ", "
                                + size(r.path("restoredMb").asLong()) + ")"
                                + (r.path("restarted").asBoolean(false) ? " et relancé." : "."))));
                if (r.hasNonNull("restartError")) {
                    failure(player, text("Redémarrage impossible : " + r.get("restartError").asText()));
                }
                player.sendMessage(buttons(r.path("wasRunning").asBoolean(false) ? joinButton(t.ref()) : startButton(t.ref()),
                        infoButton(t.ref())));
            });
        });
    }

    private void handleRestoreDeleted(Player player, String[] args) {
        if (args.length == 2) {
            apiClient.getPlayerByUuid(player.getUniqueId())
                    .thenCompose(info -> apiClient.listDeletedBackups(info.get("id").asLong()))
                    .whenComplete((r, error) -> run(() -> {
                        if (error != null) {
                            reportError(player, error, null);
                            return;
                        }
                        sendDeletedBackups(player, r.path("backups"));
                    }));
            return;
        }
        boolean confirmed = "confirm".equalsIgnoreCase(args[args.length - 1]);
        int n = confirmed ? args.length - 1 : args.length;
        if (n < 3 || n > 4) {
            usage(player, "/mcs restore deleted <n°> [nouveau-nom]", "/mcs restore deleted ");
            return;
        }
        Long backupId = parseBackupNumber(player, args[2]);
        if (backupId == null) {
            return;
        }
        String name = n == 4 ? args[3].toLowerCase(Locale.ROOT) : null;
        if (name != null && !NAME_PATTERN.matcher(name).matches()) {
            failure(player, text("Nom invalide : " + name + " (3 à 32 caractères : lettres minuscules, chiffres et -)."));
            return;
        }
        String cmd = "/mcs restore deleted " + backupId + (name == null ? "" : " " + name);
        if (!confirmed) {
            notice(player, text("Recréer le serveur de la sauvegarde n°" + backupId
                    + (name == null ? "" : " sous le nom " + name) + " ? Il revient sur sa machine d'origine, "
                    + "avec ses réglages, et compte dans les limites de son propriétaire."));
            player.sendMessage(buttons(button("Confirmer", NamedTextColor.GREEN, cmd + " confirm", "Recréer le serveur")));
            return;
        }
        pending(player, text("Recréation du serveur depuis la sauvegarde n°" + backupId + "… (quelques minutes)"));
        apiClient.getPlayerByUuid(player.getUniqueId())
                .thenCompose(info -> apiClient.restoreDeleted(backupId, info.get("id").asLong(), name))
                .whenComplete((r, error) -> run(() -> {
                    if (!player.isActive()) {
                        return;
                    }
                    if (error != null) {
                        reportError(player, error, name);
                        return;
                    }
                    String created = r.path("name").asText(name == null ? "?" : name);
                    if ("RUNNING".equals(r.path("status").asText())) {
                        notice(player, text("La recréation de " + created + " continue en arrière-plan : /mcs list dans quelques minutes."));
                        return;
                    }
                    success(player, text("").append(serverName(created)).append(text(" est recréé depuis sa sauvegarde.")));
                    player.sendMessage(buttons(joinButton(created), infoButton(created)));
                }));
    }

    private void sendDeletedBackups(Player player, JsonNode list) {
        player.sendMessage(Component.empty());
        player.sendMessage(header("Serveurs supprimés"));
        if (list.size() == 0) {
            player.sendMessage(Component.text(" Aucune sauvegarde de serveur supprimé n'est encore gardée.", NamedTextColor.GRAY));
            return;
        }
        player.sendMessage(Component.text(" Sauvegardes encore gardées après la suppression (au central).", NamedTextColor.GRAY));
        for (JsonNode b : list) {
            long id = b.path("id").asLong();
            String name = b.hasNonNull("name") ? b.get("name").asText() : b.path("ref").asText("?");
            String ago = b.hasNonNull("createdAt") ? uptime(b.get("createdAt").asText()) : null;
            String until = b.hasNonNull("expiresAt") ? remaining(b.get("expiresAt").asText()) : null;
            Component line = Component.text(" n°" + id, NamedTextColor.DARK_AQUA)
                    .append(Component.text("  " + name, NamedTextColor.WHITE))
                    .append(b.hasNonNull("owner") ? Component.text(" (" + b.get("owner").asText() + ")", NamedTextColor.GRAY)
                            : Component.empty())
                    .append(Component.text("  il y a " + (ago == null ? "?" : ago), NamedTextColor.GRAY))
                    .append(Component.text(until == null ? "" : "  supprimée " + until, NamedTextColor.DARK_GRAY));
            if (b.path("restorable").asBoolean(false)) {
                line = line.append(text(" ")).append(button("Recréer", NamedTextColor.GREEN, "/mcs restore deleted " + id,
                        "Recréer " + name + " depuis cette sauvegarde"));
            } else {
                line = line.hoverEvent(HoverEvent.showText(Component.text(
                        "Supprimé avant la mise à jour : ne peut pas être recréé", NamedTextColor.GRAY)));
            }
            player.sendMessage(line);
        }
    }

    private void handleHost(Player player, String[] args) {
        String sub = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> apiClient.getPlayerByUuid(player.getUniqueId())
                    .thenCompose(info -> apiClient.getHosts(info.get("id").asLong()))
                    .whenComplete((r, error) -> run(() -> {
                        if (error != null) {
                            reportError(player, error, null);
                            return;
                        }
                        sendHosts(player, r.path("machines"));
                    }));
            case "set", "remove" -> {
                boolean set = sub.equals("set");
                if ((set && args.length != 4) || (!set && args.length != 3)) {
                    usage(player, set ? "/mcs host set <machine> <joueur>" : "/mcs host remove <machine>",
                            set ? "/mcs host set " : "/mcs host remove ");
                    return;
                }
                long machine;
                try {
                    machine = Long.parseLong(args[2].replace("#", ""));
                } catch (NumberFormatException e) {
                    failure(player, text("Numéro de machine invalide : " + args[2] + " (voir /mcs host list)."));
                    return;
                }
                String target = set ? args[3] : null;
                if (set && !validPlayerName(player, target)) {
                    return;
                }
                apiClient.getPlayerByUuid(player.getUniqueId())
                        .thenCompose(info -> apiClient.setHost(machine, info.get("id").asLong(), target))
                        .whenComplete((r, error) -> run(() -> {
                            if (error != null) {
                                reportError(player, error, null);
                                return;
                            }
                            applyHostGroups(player, r);
                        }));
            }
            default -> usage(player, "/mcs host [list|set|remove]", "/mcs host ");
        }
    }

    private void sendHosts(Player player, JsonNode machines) {
        player.sendMessage(Component.empty());
        player.sendMessage(header("Hôtes des machines"));
        if (machines.size() == 0) {
            player.sendMessage(Component.text(" Aucune machine.", NamedTextColor.GRAY));
        }
        for (JsonNode m : machines) {
            long id = m.path("machine").asLong();
            boolean online = m.path("online").asBoolean(false);
            String host = m.path("host").isNull() || m.path("host").isMissingNode() ? null : m.path("host").asText();
            Component line = Component.text(" #" + id + " ", NamedTextColor.WHITE, TextDecoration.BOLD)
                    .append(Component.text(m.path("hostname").asText("?"), NamedTextColor.GRAY))
                    .append(Component.text("  " + m.path("region").asText("?") + " · " + m.path("servers").asInt(0) + " serv.",
                            NamedTextColor.DARK_AQUA)
                            .clickEvent(ClickEvent.suggestCommand("/mcs machine "))
                            .hoverEvent(HoverEvent.showText(Component.text(
                                    "/mcs machine region " + id + " <région>\n/mcs machine backup " + id
                                            + "\n/mcs machine evacuate " + id + " [machine] [backup]", NamedTextColor.GRAY))))
                    .append(Component.text(online ? "  ● en ligne" : "  ○ hors ligne",
                            online ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY))
                    .append(Component.text("  hôte : ", NamedTextColor.GRAY))
                    .append(host == null ? Component.text("aucun", NamedTextColor.DARK_GRAY)
                            : Component.text(host, NamedTextColor.AQUA))
                    .append(text("  "))
                    .append(suggestButton(host == null ? "Choisir" : "Changer", NamedTextColor.GREEN,
                            "/mcs host set " + id + " ", "Écris le pseudo du propriétaire de cette machine"));
            if (host != null) {
                line = line.append(text(" ")).append(button("Retirer", NamedTextColor.RED,
                        "/mcs host remove " + id, "Plus d'hôte pour la machine #" + id));
            }
            player.sendMessage(line);
        }
        player.sendMessage(Component.text(" L'hôte gère seulement les serveurs de sa machine.", NamedTextColor.DARK_GRAY));
    }

    /** Groupe LuckPerms "host" (titre) : donné au nouvel hôte, retiré à l'ancien s'il n'a plus de machine */
    private void applyHostGroups(Player player, JsonNode r) {
        long machine = r.path("machine").asLong();
        String host = r.hasNonNull("host") ? r.get("host").asText() : null;
        if (host != null) {
            success(player, text(host + " est l'hôte de la machine #" + machine + " ("
                    + r.path("hostname").asText("?") + ")."));
        } else {
            success(player, text("La machine #" + machine + " n'a plus d'hôte."));
        }
        java.util.List<java.util.concurrent.CompletableFuture<Boolean>> changes = new java.util.ArrayList<>();
        if (r.hasNonNull("hostUuid")) {
            changes.add(playerSync.setHostGroup(server, java.util.UUID.fromString(r.get("hostUuid").asText()), true));
        }
        if (r.hasNonNull("previousUuid") && !r.path("previousStillHost").asBoolean(false)) {
            changes.add(playerSync.setHostGroup(server, java.util.UUID.fromString(r.get("previousUuid").asText()), false));
            player.sendMessage(Component.text("   " + r.path("previous").asText() + " n'est plus hôte.", NamedTextColor.GRAY));
        }
        java.util.concurrent.CompletableFuture.allOf(changes.toArray(new java.util.concurrent.CompletableFuture[0]))
                .whenComplete((v, error) -> run(() -> {
                    boolean ok = error == null && changes.stream().allMatch(f -> Boolean.TRUE.equals(f.getNow(false)));
                    if (!ok) {
                        notice(player, text("Titre ʜᴏsᴛ à mettre à la main : lp user <pseudo> parent add|remove host"));
                    }
                }));
    }

    // ============================================================ console / move ====

    private void handleConsole(Player player, String[] args) {
        if (args.length < 3) {
            usage(player, "/mcs console <serveur> <commande>", "/mcs console ");
            return;
        }
        String command = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        withServer(player, args[1], t -> {
            if (!t.can("CONSOLE")) {
                failure(player, text("Tu n'as pas accès à la console de ce serveur."));
                return;
            }
            send(player, Component.text(t.ref() + " > ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(command, NamedTextColor.WHITE)));
            afterApi(player, apiClient.console(t.id(), t.playerId(), command), t.ref(), r -> {
                String output = r.path("output").asText("").strip();
                if (output.isEmpty()) {
                    player.sendMessage(Component.text("   (pas de réponse)", NamedTextColor.DARK_GRAY));
                    return;
                }
                String[] lines = output.split("\n");
                for (int i = 0; i < Math.min(lines.length, 20); i++) {
                    player.sendMessage(Component.text("   " + lines[i], NamedTextColor.GRAY));
                }
                if (lines.length > 20) {
                    player.sendMessage(Component.text("   … (" + (lines.length - 20) + " lignes de plus)", NamedTextColor.DARK_GRAY));
                }
            });
        });
    }

    private void handleMove(Player player, String[] args) {
        if (args.length != 3) {
            usage(player, "/mcs move <joueur> <serveur>", "/mcs move ");
            return;
        }
        String target = args[1];
        if (!validPlayerName(player, target)) {
            return;
        }
        withServer(player, args[2], t -> {
            if (!t.can("MOVE")) {
                failure(player, text("Seuls l'hébergeur de la machine et les admins peuvent amener un joueur ici."));
                return;
            }
            afterApi(player, apiClient.move(t.id(), t.playerId(), target), t.ref(),
                    r -> success(player, text(target + " est envoyé sur ").append(serverName(t.ref()))
                            .append(text(" (une seule fois : il ne pourra pas revenir seul)."))));
        });
    }

    // ============================================================ affichage des infos ====

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

    private void sendInfo(Player player, JsonNode s, Target t) {
        String name = t.ref();
        String status = s.path("status").asText("?");
        boolean running = "RUNNING".equals(status);

        // En-tête : nom, état, type et version, durée en ligne
        player.sendMessage(Component.empty());
        player.sendMessage(header(name));
        Component line = Component.text(" ").append(state(status, s.path("health").asText("unknown")))
                .append(Component.text("  ·  " + capitalize(s.path("serverType").asText("?")) + " "
                        + s.path("minecraftVersion").asText("")
                        + (s.has("java") ? "  ·  Java " + s.path("java").asInt()
                                + (s.path("javaAuto").asBoolean(true) ? "" : " (choisi)") : ""), NamedTextColor.GRAY)
                        .hoverEvent(HoverEvent.showText(Component.text("Changer de version de Java : /mcs java " + name)))
                        .clickEvent(ClickEvent.suggestCommand("/mcs java " + name + " ")));
        String up = running && s.hasNonNull("lastStartedAt") ? uptime(s.get("lastStartedAt").asText()) : null;
        if (up != null) {
            line = line.append(Component.text("  ·  depuis " + up, NamedTextColor.GRAY));
        }
        player.sendMessage(line);

        // Accès : public/privé, invités, rôle du joueur s'il n'est pas le propriétaire
        JsonNode srv = t.server();
        String relation = srv.path("relation").asText("");
        int members = srv.path("members").asInt(0);
        Component access = Component.text(" Accès : ", NamedTextColor.GRAY)
                .append(srv.path("isPublic").asBoolean(false)
                        ? Component.text("public", NamedTextColor.GREEN)
                        : Component.text("privé", NamedTextColor.GOLD))
                .append(Component.text("  ·  " + members + (members > 1 ? " invités" : " invité"), NamedTextColor.GRAY)
                        .clickEvent(ClickEvent.runCommand("/mcs members " + name))
                        .hoverEvent(HoverEvent.showText(Component.text("Voir les accès", NamedTextColor.GRAY))));
        if (!"proprietaire".equals(relation)) {
            access = access.append(Component.text("  ·  à " + srv.path("ownerUsername").asText("?")
                    + ", tu es " + relationLabel(relation), NamedTextColor.DARK_AQUA));
        }
        player.sendMessage(access);

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

        // Dernière sauvegarde
        String backupAgo = s.hasNonNull("lastBackupAt") ? uptime(s.get("lastBackupAt").asText()) : null;
        Component backupLine = Component.text(" Sauvegarde : ", NamedTextColor.GRAY)
                .append(backupAgo == null
                        ? Component.text("aucune pour l'instant", NamedTextColor.DARK_GRAY)
                        : Component.text("il y a " + backupAgo, NamedTextColor.WHITE));
        if (t.can("POWER")) {
            backupLine = backupLine.append(text("  "))
                    .append(button("Sauvegarder", NamedTextColor.GREEN, "/mcs backup " + name, "Sauvegarder " + name + " maintenant"))
                    .append(text(" "))
                    .append(button("Historique", NamedTextColor.AQUA, "/mcs backups " + name, "Sauvegardes de " + name));
        }
        player.sendMessage(backupLine);

        // Actions, selon les droits du joueur
        Component actions = Component.text(" ");
        if (running) {
            if (t.can("JOIN")) {
                actions = actions.append(joinButton(name)).append(text(" "));
            }
            if (t.can("POWER")) {
                actions = actions
                        .append(button("Redémarrer", NamedTextColor.YELLOW, "/mcs restart " + name, "Redémarrer " + name))
                        .append(text(" "))
                        .append(button("Arrêter", NamedTextColor.RED, "/mcs stop " + name, "Arrêter " + name))
                        .append(text(" "));
            }
            if (t.can("CONSOLE")) {
                actions = actions.append(suggestButton("Console", NamedTextColor.LIGHT_PURPLE, "/mcs console " + name + " ",
                        "Écris une commande pour le serveur, ex. : op TonPseudo")).append(text(" "));
            }
        } else if ("STOPPED".equals(status) || "ERROR".equals(status)) {
            if (t.can("POWER")) {
                actions = actions.append(startButton(name)).append(text(" "));
            }
            if (t.can("DELETE")) {
                actions = actions.append(button("Supprimer", NamedTextColor.DARK_RED, "/mcs delete " + name,
                        "Supprimer " + name + " (une confirmation est demandée)")).append(text(" "));
            }
        }
        actions = actions.append(button("Accès", NamedTextColor.GOLD, "/mcs members " + name, "Invités, public ou privé"))
                .append(text(" "))
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
        playerSync.sync(player)
                .thenCompose(v -> apiClient.getPlayerByUuid(player.getUniqueId()))
                .thenCompose(playerInfo -> apiClient.getPlayerQuota(playerInfo.get("id").asLong()))
                .whenComplete((q, error) -> run(() -> {
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
        long deletedHeld = q.path("deletedHeld").asLong(0);
        long totalRam = q.path("totalRamMb").asLong(0);
        long ramUsed = q.path("ramUsedMb").asLong(0);
        long ramLeft = Math.max(0, q.path("ramRemainingMb").asLong(totalRam - ramUsed));
        long totalCpu = q.path("totalCpuCores").asLong(0);
        long cpuUsed = q.path("cpuUsedCores").asLong(0);
        long cpuLeft = Math.max(0, q.path("cpuRemainingCores").asLong(totalCpu - cpuUsed));

        player.sendMessage(Component.empty());
        player.sendMessage(header("Ton quota"));

        // Même présentation que /mcs info : barre, libellé, valeurs
        long slotsUsed = serversUsed + deletedHeld;
        player.sendMessage(Component.text(" ").append(bar(ratio(slotsUsed, maxServers))).append(label("Serveurs"))
                .append(Component.text(slotsUsed + " / " + maxServers, NamedTextColor.WHITE))
                .append(Component.text(slotsUsed >= maxServers ? "  limite atteinte" : "  reste " + (maxServers - slotsUsed),
                        slotsUsed >= maxServers ? NamedTextColor.RED : NamedTextColor.GRAY))
                .hoverEvent(HoverEvent.showText(Component.text(
                        "Nombre de serveurs que tu peux posséder,\nallumés ou éteints."
                                + (deletedHeld > 0 ? "\nDont " + deletedHeld + " serveur(s) supprimé(s) dont les sauvegardes\n"
                                + "sont encore gardées (voir /mcs restore deleted)." : ""), NamedTextColor.GRAY))));
        if (deletedHeld > 0) {
            player.sendMessage(Component.text(" dont " + deletedHeld + " supprimé(s), sauvegardes encore gardées ", NamedTextColor.DARK_GRAY)
                    .append(button("Voir", NamedTextColor.AQUA, "/mcs restore deleted", "Serveurs supprimés")));
        }

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

    // Connexion par le proxy (l'accès est revérifié par le contrôle de pré-connexion)
    private void sendToServer(Player player, String serverName) {
        java.util.Optional<com.velocitypowered.api.proxy.server.RegisteredServer> target = server.getServer(serverName);
        if (target.isEmpty()) {
            failure(player, text("Ce serveur n'est pas encore joignable, réessaie dans un instant."));
            return;
        }
        player.createConnectionRequest(target.get()).fireAndForget();
    }
}
