package vinch.img.farmmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.List;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** /imgfarm … (commande du client : le serveur ne la voit jamais) */
final class Commands {

    private static final SuggestionProvider<FabricClientCommandSource> ITEMS =
            (c, b) -> SharedSuggestionProvider.suggest(ImgFarmMod.allItemIds(), b);
    private static final SuggestionProvider<FabricClientCommandSource> GOALS =
            (c, b) -> SharedSuggestionProvider.suggest(List.of("30m", "1h", "640"), b);
    private static final SuggestionProvider<FabricClientCommandSource> TYPES =
            (c, b) -> SharedSuggestionProvider.suggest(ImgFarmMod.TYPES.keySet(), b);
    private static final SuggestionProvider<FabricClientCommandSource> PARTS =
            (c, b) -> SharedSuggestionProvider.suggest(List.of("base", "module"), b);

    private Commands() {
    }

    private static ImgFarmMod mod() {
        return ImgFarmMod.INSTANCE;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> run(String name, Runnable action) {
        return literal(name).executes(c -> {
            action.run();
            return 1;
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> session(String name, boolean auto) {
        return literal(name)
                .executes(c -> {
                    ImgFarmMod.say(Msg.error("Indique l'item : /imgfarm " + name + " <item> [30m | 640]"));
                    return 0;
                })
                .then(argument("item", StringArgumentType.word()).suggests(ITEMS)
                        .executes(c -> {
                            mod().start(auto, getString(c, "item"), null);
                            return 1;
                        })
                        .then(argument("objectif", StringArgumentType.word()).suggests(GOALS)
                                .executes(c -> {
                                    mod().start(auto, getString(c, "item"), getString(c, "objectif"));
                                    return 1;
                                })));
    }

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
        dispatcher.register(ClientCommands.literal("imgfarm")
                .executes(c -> {
                    mod().help();
                    return 1;
                })
                .then(run("aide", () -> mod().help()))
                .then(run("help", () -> mod().help()))
                .then(session("start", false))
                .then(session("auto", true))
                .then(run("coffre", () -> mod().addContainer()))
                .then(literal("construction")
                        .executes(c -> {
                            mod().startConstruction(null);
                            return 1;
                        })
                        .then(argument("partie", StringArgumentType.word()).suggests(PARTS).executes(c -> {
                            mod().startConstruction(getString(c, "partie"));
                            return 1;
                        })))
                .then(run("pause", () -> mod().pause()))
                .then(run("reprendre", () -> mod().resume()))
                .then(run("stop", () -> mod().stop()))
                .then(run("annuler", () -> mod().cancel()))
                .then(literal("type").then(argument("type", StringArgumentType.word()).suggests(TYPES).executes(c -> {
                    mod().setType(getString(c, "type"));
                    return 1;
                })))
                .then(run("envoyer", () -> mod().send()))
                .then(run("jeter", () -> mod().discard()))
                .then(run("hud", () -> mod().toggleHud()))
                .then(run("compte", () -> mod().account()))
                .then(run("delier", () -> mod().unlink()))
                // Lier : tapé par le joueur seulement (jamais depuis un lien envoyé par un serveur)
                .then(literal("lier").requires(FabricClientCommandSource::attended)
                        .then(argument("code", StringArgumentType.word()).executes(c -> {
                            mod().link(getString(c, "code"));
                            return 1;
                        })))
                .then(argument("recherche", StringArgumentType.greedyString()).suggests(ITEMS).executes(c -> {
                    mod().best(getString(c, "recherche"));
                    return 1;
                })));
    }
}
