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

import java.util.regex.Pattern;

public class ServerCommand implements CommandExecutor {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9-]{3,32}$");

    private final McsLobbyPlugin plugin;

    public ServerCommand(McsLobbyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Cette commande est réservée aux joueurs.").color(NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
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

        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(Component.text("Commandes disponibles :").color(NamedTextColor.YELLOW));
        player.sendMessage(Component.text("  /mcs create <type> <version> <nom> [ram_mb] [cpu_cores]").color(NamedTextColor.AQUA));
        player.sendMessage(Component.text("    Types : PAPER, SPIGOT, FABRIC, FORGE, VANILLA").color(NamedTextColor.GRAY));
        player.sendMessage(Component.text("    RAM : en Mo, 512 minimum (défaut : 1024) — limitée par ton quota (/mcs quota)").color(NamedTextColor.GRAY));
        player.sendMessage(Component.text("    CPU : en cœurs (défaut : 1) — limité par ton quota").color(NamedTextColor.GRAY));
        player.sendMessage(Component.text("  /mcs list").color(NamedTextColor.AQUA));
        player.sendMessage(Component.text("  /mcs delete <nom>").color(NamedTextColor.AQUA));
        player.sendMessage(Component.text("  /mcs join <nom>").color(NamedTextColor.AQUA));
        player.sendMessage(Component.text("  /mcs start <nom>").color(NamedTextColor.AQUA));
        player.sendMessage(Component.text("  /mcs stop <nom>").color(NamedTextColor.AQUA));
        player.sendMessage(Component.text("  /mcs restart <nom>").color(NamedTextColor.AQUA));
        player.sendMessage(Component.text("  /mcs quota").color(NamedTextColor.AQUA));
        player.sendMessage(Component.text("  /mcs info <nom>").color(NamedTextColor.AQUA));
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length < 4 || args.length > 6) {
            player.sendMessage(Component.text("Usage : /mcs create <type> <version> <nom> [ram_mb] [cpu_cores]").color(NamedTextColor.RED));
            player.sendMessage(Component.text("Défauts : RAM=1024 Mo, CPU=1 core").color(NamedTextColor.GRAY));
            return;
        }

        String type = args[1].toUpperCase();
        String version = args[2];
        String name = args[3].toLowerCase();
        int ramMb = 1024;
        int cpuCores = 1;

        if (args.length >= 5) {
            try {
                ramMb = Integer.parseInt(args[4]);
                // Pas de maximum ici : l'API vérifie le budget du joueur et la place sur les machines
                if (ramMb < 512) {
                    player.sendMessage(Component.text("RAM minimum : 512 Mo.").color(NamedTextColor.RED));
                    return;
                }
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("RAM invalide. Utilise un nombre en Mo.").color(NamedTextColor.RED));
                return;
            }
        }

        if (args.length == 6) {
            try {
                cpuCores = Integer.parseInt(args[5]);
                if (cpuCores < 1) {
                    player.sendMessage(Component.text("CPU minimum : 1 cœur.").color(NamedTextColor.RED));
                    return;
                }
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("CPU invalide. Utilise un nombre entier.").color(NamedTextColor.RED));
                return;
            }
        }

        if (!NAME_PATTERN.matcher(name).matches()) {
            player.sendMessage(Component.text("Nom invalide. Utilise 3-32 caractères : lettres minuscules, chiffres, tirets.").color(NamedTextColor.RED));
            return;
        }

        if (!type.equals("PAPER") && !type.equals("SPIGOT") && !type.equals("FABRIC")
                && !type.equals("FORGE") && !type.equals("VANILLA")) {
            player.sendMessage(Component.text("Type invalide. Types possibles : PAPER, SPIGOT, FABRIC, FORGE, VANILLA").color(NamedTextColor.RED));
            return;
        }

        player.sendMessage(Component.text("⏳ Création du serveur en cours, patiente...").color(NamedTextColor.YELLOW));

        final int finalRamMb = ramMb;
        final int finalCpuCores = cpuCores;

        plugin.syncPlayerLimits(player.getUniqueId(), player.getName())
                .thenCompose(v -> plugin.getApiClient().getPlayerByUuid(player.getUniqueId()))
                .thenCompose(playerInfo -> {
                    long playerId = playerInfo.get("id").asLong();
                    return plugin.getApiClient().createServer(
                            playerId,
                            name,
                            name,
                            type,
                            version,
                            finalRamMb,
                            finalCpuCores,
                            5000
                    );
                })
                .whenComplete((result, error) -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            String msg = error.getMessage();
                            if (msg.contains("Limite de serveurs atteinte")) {
                                player.sendMessage(Component.text("❌ Tu as atteint ta limite de serveurs.").color(NamedTextColor.RED));
                            } else if (msg.contains("Pas assez de RAM")) {
                                player.sendMessage(Component.text("❌ " + msg.substring(msg.indexOf("Pas assez"))).color(NamedTextColor.RED));
                            } else if (msg.contains("Pas assez de CPU")) {
                                player.sendMessage(Component.text("❌ " + msg.substring(msg.indexOf("Pas assez"))).color(NamedTextColor.RED));
                            } else if (msg.contains("déjà un serveur")) {
                                player.sendMessage(Component.text("❌ Tu as déjà un serveur avec ce nom.").color(NamedTextColor.RED));
                            } else {
                                player.sendMessage(Component.text("❌ Erreur : " + msg).color(NamedTextColor.RED));
                                plugin.getLogger().warning("Erreur création serveur pour " + player.getName() + " : " + msg);
                            }
                            return;
                        }

                        String serverName = result.get("name").asText();

                        player.sendMessage(Component.text("✅ Serveur '" + serverName + "' créé avec succès !").color(NamedTextColor.GREEN));
                        long storage = result.path("storageMb").asLong(0);
                        player.sendMessage(Component.text("RAM : " + size(finalRamMb) + "  ·  CPU : " + finalCpuCores
                                + (finalCpuCores > 1 ? " cœurs" : " cœur")
                                + (storage > 0 ? "  ·  Disque : " + size(storage) : "")).color(NamedTextColor.GRAY));
                        player.sendMessage(Component.text("Connecte-toi avec /mcs join " + serverName).color(NamedTextColor.AQUA));
                    });
                });
    }

    private void handleList(Player player) {
        player.sendMessage(Component.text("⏳ Récupération de tes serveurs...").color(NamedTextColor.YELLOW));

        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
                .thenCompose(playerInfo -> {
                    long playerId = playerInfo.get("id").asLong();
                    return plugin.getApiClient().getPlayerServers(playerId);
                })
                .whenComplete((result, error) -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            player.sendMessage(Component.text("❌ Erreur : " + error.getMessage()).color(NamedTextColor.RED));
                            return;
                        }

                        if (!result.has("servers") || result.get("servers").size() == 0) {
                            player.sendMessage(Component.text("Tu n'as aucun serveur.").color(NamedTextColor.GRAY));
                            return;
                        }

                        player.sendMessage(Component.text("Tes serveurs :").color(NamedTextColor.YELLOW));
                        for (JsonNode server : result.get("servers")) {
                            String name = server.get("name").asText();
                            String status = server.get("status").asText();
                            NamedTextColor statusColor = "RUNNING".equals(status) ? NamedTextColor.GREEN : NamedTextColor.GRAY;
                            player.sendMessage(Component.text("  - " + name + " (").color(NamedTextColor.AQUA)
                                    .append(Component.text(status).color(statusColor))
                                    .append(Component.text(")").color(NamedTextColor.AQUA)));
                        }
                    });
                });
    }

    private void handleDelete(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(Component.text("Usage : /mcs delete <nom>").color(NamedTextColor.RED));
            return;
        }

        String name = args[1].toLowerCase();
        player.sendMessage(Component.text("⏳ Suppression du serveur '" + name + "'...").color(NamedTextColor.YELLOW));

        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
                .thenCompose(playerInfo -> {
                    long playerId = playerInfo.get("id").asLong();
                    return plugin.getApiClient().deleteServer(name, playerId);
                })
                .whenComplete((result, error) -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            String msg = error.getMessage();
                            if (msg.contains("propriétaire")) {
                                player.sendMessage(Component.text("❌ Tu n'es pas le propriétaire de ce serveur.").color(NamedTextColor.RED));
                            } else if (msg.contains("introuvable")) {
                                player.sendMessage(Component.text("❌ Serveur introuvable.").color(NamedTextColor.RED));
                            } else {
                                player.sendMessage(Component.text("❌ Erreur : " + msg).color(NamedTextColor.RED));
                            }
                            return;
                        }
                        player.sendMessage(Component.text("✅ Serveur '" + name + "' supprimé avec succès !").color(NamedTextColor.GREEN));
                    });
                });
    }

    private void handleJoin(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(Component.text("Usage : /mcs join <nom>").color(NamedTextColor.RED));
            return;
        }

        String serverName = args[1].toLowerCase();

        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
                .thenCompose(playerInfo -> {
                    long playerId = playerInfo.get("id").asLong();
                    return plugin.getApiClient().getServerByOwnerAndName(playerId, serverName);
                })
                .whenComplete((result, error) -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            if (error.getMessage().contains("404")) {
                                player.sendMessage(Component.text("❌ Tu n'as pas de serveur '" + serverName + "'.").color(NamedTextColor.RED));
                            } else {
                                player.sendMessage(Component.text("❌ Erreur : " + error.getMessage()).color(NamedTextColor.RED));
                            }
                            return;
                        }

                        String velocityName = result.get("velocityName").asText();
                        String status = result.get("status").asText();

                        if (!"RUNNING".equals(status)) {
                            player.sendMessage(Component.text("❌ Ton serveur n'est pas en marche (status : " + status + "). Utilise /mcs start " + serverName).color(NamedTextColor.RED));
                            return;
                        }

                        player.sendMessage(Component.text("⏳ Connexion à " + serverName + "...").color(NamedTextColor.YELLOW));
                        sendToServer(player, velocityName);
                    });
                });
    }

    private void handleInfo(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(Component.text("Usage : /mcs info <nom>").color(NamedTextColor.RED));
            return;
        }
        String name = args[1].toLowerCase();

        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
                .thenCompose(playerInfo -> plugin.getApiClient()
                        .getServerByOwnerAndName(playerInfo.get("id").asLong(), name))
                .thenCompose(server -> plugin.getApiClient().getServerStats(server.get("id").asLong()))
                .whenComplete((stats, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        String msg = String.valueOf(error.getMessage());
                        if (msg.contains("404") || msg.contains("introuvable")) {
                            player.sendMessage(Component.text("❌ Serveur introuvable.").color(NamedTextColor.RED));
                        } else {
                            player.sendMessage(Component.text("❌ Erreur : " + msg).color(NamedTextColor.RED));
                        }
                        return;
                    }
                    sendInfo(player, stats);
                }));
    }

    // ------------------------------------------------------------ /mcs info ----

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
        return (go == Math.floor(go) ? String.valueOf((long) go) : String.format(java.util.Locale.FRANCE, "%.1f", go)) + " Go";
    }

    private static String percent(double ratio) {
        return String.format(java.util.Locale.FRANCE, "%.0f %%", ratio * 100);
    }

    // La police de Minecraft est proportionnelle : les barres (toutes de la même
    // largeur) viennent en premier pour rester alignées, le libellé ensuite
    private static Component label(String text) {
        return Component.text("  " + text + "  ").color(NamedTextColor.GRAY);
    }

    private static Component button(String text, NamedTextColor color, String command, String hover) {
        return Component.text("[" + text + "]").color(color).decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover).color(NamedTextColor.GRAY)));
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
        String health = s.path("health").asText("unknown");

        Component state = switch (status) {
            case "RUNNING" -> "unhealthy".equals(health)
                    ? Component.text("● En ligne, ne répond plus").color(NamedTextColor.GOLD)
                    : Component.text("● En ligne").color(NamedTextColor.GREEN);
            case "STARTING" -> Component.text("◐ Démarrage…").color(NamedTextColor.YELLOW);
            case "CREATING" -> Component.text("◐ Création…").color(NamedTextColor.YELLOW);
            case "STOPPING" -> Component.text("◐ Arrêt en cours…").color(NamedTextColor.YELLOW);
            case "STOPPED" -> Component.text("○ Arrêté").color(NamedTextColor.GRAY);
            case "ERROR" -> Component.text("✖ Erreur").color(NamedTextColor.RED);
            default -> Component.text(status).color(NamedTextColor.GRAY);
        };
        boolean running = "RUNNING".equals(status);

        // En-tête : nom, état, type et version, durée en ligne
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("━━━━━━ ").color(NamedTextColor.DARK_GRAY)
                .append(Component.text(name).color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                .append(Component.text(" ━━━━━━").color(NamedTextColor.DARK_GRAY)));
        Component line = Component.text(" ").append(state)
                .append(Component.text("  ·  " + capitalize(s.path("serverType").asText("?")) + " "
                        + s.path("minecraftVersion").asText("")).color(NamedTextColor.GRAY));
        String up = running && s.hasNonNull("lastStartedAt") ? uptime(s.get("lastStartedAt").asText()) : null;
        if (up != null) {
            line = line.append(Component.text("  ·  depuis " + up).color(NamedTextColor.GRAY));
        }
        player.sendMessage(line);

        // Joueurs
        int players = s.path("players").asInt(0);
        player.sendMessage(Component.text(" Joueurs : ").color(NamedTextColor.GRAY)
                .append(Component.text(players + (players > 1 ? " connectés" : " connecté"))
                        .color(players > 0 ? NamedTextColor.AQUA : NamedTextColor.WHITE)));

        boolean measured = s.has("memUsedMb") && running;

        // CPU : rapporté aux cœurs alloués (docker compte 100 % par cœur)
        int cores = Math.max(1, s.path("allocatedCpuCores").asInt(1));
        if (measured) {
            double cpuRaw = s.path("cpuPercent").asDouble(0);
            double cpu = cpuRaw / 100.0 / cores;
            player.sendMessage(Component.text(" ").append(bar(cpu)).append(label("CPU"))
                    .append(Component.text(percent(cpu) + " de " + cores + (cores > 1 ? " cœurs" : " cœur"))
                            .color(NamedTextColor.WHITE))
                    .hoverEvent(HoverEvent.showText(Component.text(String.format(java.util.Locale.FRANCE,
                            "%.1f %% d'un cœur, sur %d cœur(s) alloué(s)", cpuRaw, cores)).color(NamedTextColor.GRAY))));
        }

        // RAM : utilisée réellement / RAM choisie par le joueur (le tas Java)
        long allocated = s.path("allocatedRamMb").asLong(0);
        if (measured && allocated > 0) {
            long used = s.path("memUsedMb").asLong(0);
            player.sendMessage(Component.text(" ").append(bar((double) used / allocated)).append(label("RAM"))
                    .append(Component.text(size(used) + " / " + size(allocated)).color(NamedTextColor.WHITE))
                    .hoverEvent(HoverEvent.showText(Component.text(
                            "Mémoire réellement utilisée / RAM choisie pour le serveur.\n"
                                    + "Java réserve " + size(allocated) + " mais n'occupe que ce dont il a besoin.\n"
                                    + "Limite du conteneur (avec la marge de Java) : "
                                    + size(s.path("memLimitMb").asLong(0))).color(NamedTextColor.GRAY))));
        }

        // Disque : mesuré toutes les 10 minutes
        long quota = s.path("allocatedStorageMb").asLong(0);
        if (s.hasNonNull("diskUsedMb") && quota > 0) {
            long disk = s.path("diskUsedMb").asLong(0);
            boolean over = s.path("diskQuotaExceeded").asBoolean(false);
            player.sendMessage(Component.text(" ").append(bar((double) disk / quota)).append(label("Disque"))
                    .append(Component.text(size(disk) + " / " + size(quota)).color(NamedTextColor.WHITE))
                    .append(over ? Component.text("  quota dépassé").color(NamedTextColor.RED) : Component.empty())
                    .hoverEvent(HoverEvent.showText(Component.text(
                            "Espace utilisé par le monde, les plugins et les mods.\nMesuré toutes les 10 minutes.")
                            .color(NamedTextColor.GRAY))));
        } else if (quota > 0) {
            player.sendMessage(Component.text(" Disque : ").color(NamedTextColor.GRAY)
                    .append(Component.text("mesure en cours… (quota " + size(quota) + ")").color(NamedTextColor.DARK_GRAY)));
        }

        if (!measured && running) {
            player.sendMessage(Component.text(" Mesures disponibles dans moins de 30 s").color(NamedTextColor.DARK_GRAY));
        }

        // Actions
        Component actions = Component.text(" ");
        if (running) {
            actions = actions.append(button("Rejoindre", NamedTextColor.GREEN, "/mcs join " + name, "Aller sur " + name))
                    .append(Component.text(" "))
                    .append(button("Redémarrer", NamedTextColor.YELLOW, "/mcs restart " + name, "Redémarrer " + name))
                    .append(Component.text(" "))
                    .append(button("Arrêter", NamedTextColor.RED, "/mcs stop " + name, "Arrêter " + name));
        } else if ("STOPPED".equals(status) || "ERROR".equals(status)) {
            actions = actions.append(button("Démarrer", NamedTextColor.GREEN, "/mcs start " + name, "Démarrer " + name));
        }
        actions = actions.append(Component.text(" "))
                .append(button("Actualiser", NamedTextColor.AQUA, "/mcs info " + name, "Mettre à jour ces informations"));
        player.sendMessage(actions);
    }

    private static String capitalize(String type) {
        if (type == null || type.isEmpty()) {
            return type;
        }
        return type.charAt(0) + type.substring(1).toLowerCase();
    }

    private void handleStart(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(Component.text("Usage : /mcs start <nom>").color(NamedTextColor.RED));
            return;
        }
        String name = args[1].toLowerCase();
        player.sendMessage(Component.text("⏳ Démarrage du serveur '" + name + "'...").color(NamedTextColor.YELLOW));

        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
                .thenCompose(playerInfo -> {
                    long playerId = playerInfo.get("id").asLong();
                    return plugin.getApiClient().startServer(name, playerId);
                })
                .whenComplete((result, error) -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            String msg = error.getMessage();
                            if (msg.contains("déjà en marche")) {
                                player.sendMessage(Component.text("❌ Le serveur est déjà en marche.").color(NamedTextColor.RED));
                            } else if (msg.contains("introuvable")) {
                                player.sendMessage(Component.text("❌ Serveur introuvable.").color(NamedTextColor.RED));
                            } else {
                                player.sendMessage(Component.text("❌ Erreur : " + msg).color(NamedTextColor.RED));
                            }
                            return;
                        }
                        player.sendMessage(Component.text("✅ Serveur '" + name + "' démarré !").color(NamedTextColor.GREEN));
                        player.sendMessage(Component.text("Connecte-toi avec /mcs join " + name).color(NamedTextColor.AQUA));
                    });
                });
    }

    private void handleStop(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(Component.text("Usage : /mcs stop <nom>").color(NamedTextColor.RED));
            return;
        }
        String name = args[1].toLowerCase();
        player.sendMessage(Component.text("⏳ Arrêt du serveur '" + name + "'...").color(NamedTextColor.YELLOW));

        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
                .thenCompose(playerInfo -> {
                    long playerId = playerInfo.get("id").asLong();
                    return plugin.getApiClient().stopServer(name, playerId);
                })
                .whenComplete((result, error) -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            String msg = error.getMessage();
                            if (msg.contains("déjà arrêté")) {
                                player.sendMessage(Component.text("❌ Le serveur est déjà arrêté.").color(NamedTextColor.RED));
                            } else if (msg.contains("introuvable")) {
                                player.sendMessage(Component.text("❌ Serveur introuvable.").color(NamedTextColor.RED));
                            } else {
                                player.sendMessage(Component.text("❌ Erreur : " + msg).color(NamedTextColor.RED));
                            }
                            return;
                        }
                        player.sendMessage(Component.text("✅ Serveur '" + name + "' arrêté !").color(NamedTextColor.GREEN));
                    });
                });
    }

    private void handleRestart(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(Component.text("Usage : /mcs restart <nom>").color(NamedTextColor.RED));
            return;
        }
        String name = args[1].toLowerCase();
        player.sendMessage(Component.text("⏳ Redémarrage du serveur '" + name + "'...").color(NamedTextColor.YELLOW));

        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
                .thenCompose(playerInfo -> {
                    long playerId = playerInfo.get("id").asLong();
                    return plugin.getApiClient().restartServer(name, playerId);
                })
                .whenComplete((result, error) -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            player.sendMessage(Component.text("❌ Erreur : " + error.getMessage()).color(NamedTextColor.RED));
                            return;
                        }
                        player.sendMessage(Component.text("✅ Serveur '" + name + "' redémarré !").color(NamedTextColor.GREEN));
                    });
                });
    }

    private void handleQuota(Player player) {
        player.sendMessage(Component.text("⏳ Récupération de ton quota...").color(NamedTextColor.YELLOW));

        plugin.syncPlayerLimits(player.getUniqueId(), player.getName())
                .thenCompose(v -> plugin.getApiClient().getPlayerByUuid(player.getUniqueId()))
                .thenCompose(playerInfo -> {
                    long playerId = playerInfo.get("id").asLong();
                    return plugin.getApiClient().getPlayerQuota(playerId);
                })
                .whenComplete((result, error) -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            player.sendMessage(Component.text("❌ Erreur : " + error.getMessage()).color(NamedTextColor.RED));
                            return;
                        }

                        int maxServers = result.get("maxServers").asInt();
                        int serversUsed = result.get("serversUsed").asInt();
                        int totalRam = result.get("totalRamMb").asInt();
                        int ramUsed = result.get("ramUsedMb").asInt();
                        int ramRemaining = result.get("ramRemainingMb").asInt();
                        int totalCpu = result.get("totalCpuCores").asInt();
                        int cpuUsed = result.get("cpuUsedCores").asInt();
                        int cpuRemaining = result.get("cpuRemainingCores").asInt();

                        player.sendMessage(Component.text("=== Ton quota ===").color(NamedTextColor.YELLOW));
                        player.sendMessage(Component.text("Serveurs : " + serversUsed + " / " + maxServers).color(NamedTextColor.AQUA));
                        player.sendMessage(Component.text("RAM : " + ramUsed + " / " + totalRam + " Mo (reste " + ramRemaining + " Mo)").color(NamedTextColor.AQUA));
                        player.sendMessage(Component.text("CPU : " + cpuUsed + " / " + totalCpu + " cores (reste " + cpuRemaining + ")").color(NamedTextColor.AQUA));
                    });
                });
    }

    // Demande au proxy (plugin proxymanager) d'envoyer le joueur vers ce serveur
    private void sendToServer(Player player, String serverName) {
        player.sendPluginMessage(plugin, "mcs:connect",
                serverName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}