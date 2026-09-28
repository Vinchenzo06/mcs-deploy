package vinch.mcs.lobby.commands;

import com.fasterxml.jackson.databind.JsonNode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
            default -> sendUsage(player);
        }

        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(Component.text("Commandes disponibles :").color(NamedTextColor.YELLOW));
        player.sendMessage(Component.text("  /mcs create <type> <version> <nom> [ram_mb] [cpu_cores]").color(NamedTextColor.AQUA));
        player.sendMessage(Component.text("    Types : PAPER, SPIGOT, FABRIC, FORGE, VANILLA").color(NamedTextColor.GRAY));
        player.sendMessage(Component.text("    RAM : 512-32768 Mo (défaut : 1024)").color(NamedTextColor.GRAY));
        player.sendMessage(Component.text("    CPU : 1-32 cores (défaut : 1)").color(NamedTextColor.GRAY));
        player.sendMessage(Component.text("  /mcs list").color(NamedTextColor.AQUA));
        player.sendMessage(Component.text("  /mcs delete <nom>").color(NamedTextColor.AQUA));
        player.sendMessage(Component.text("  /mcs join <nom>").color(NamedTextColor.AQUA));
        player.sendMessage(Component.text("  /mcs start <nom>").color(NamedTextColor.AQUA));
        player.sendMessage(Component.text("  /mcs stop <nom>").color(NamedTextColor.AQUA));
        player.sendMessage(Component.text("  /mcs restart <nom>").color(NamedTextColor.AQUA));
        player.sendMessage(Component.text("  /mcs quota").color(NamedTextColor.AQUA));
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
                if (ramMb < 512) {
                    player.sendMessage(Component.text("RAM minimum : 512 Mo.").color(NamedTextColor.RED));
                    return;
                }
                if (ramMb > 32768) {
                    player.sendMessage(Component.text("RAM maximum : 32768 Mo.").color(NamedTextColor.RED));
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
                    player.sendMessage(Component.text("CPU minimum : 1 core.").color(NamedTextColor.RED));
                    return;
                }
                if (cpuCores > 32) {
                    player.sendMessage(Component.text("CPU maximum : 32 cores.").color(NamedTextColor.RED));
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

        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
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
                        int port = result.get("port").asInt();

                        player.sendMessage(Component.text("✅ Serveur '" + serverName + "' créé avec succès !").color(NamedTextColor.GREEN));
                        player.sendMessage(Component.text("📡 Port : " + port + " | RAM : " + finalRamMb + " Mo | CPU : " + finalCpuCores + " core(s)").color(NamedTextColor.GRAY));
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

        plugin.getApiClient().getPlayerByUuid(player.getUniqueId())
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