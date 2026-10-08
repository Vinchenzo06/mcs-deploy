package vinch.mcs.proxymanager;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache l'IP réelle des joueurs aux serveurs.
 *
 * En forwarding "legacy" (BungeeCord), Velocity place l'IP du joueur dans le
 * paquet Handshake envoyé au serveur : "hôte\0IP\0UUID\0propriétés". Un
 * propriétaire de serveur (logs, plugin) pourrait ainsi récolter les IP de tous
 * ses visiteurs. On remplace cette IP par une pseudo-IP stable : HMAC(clé, IP)
 * projeté dans 10.0.0.0/8. Le même joueur (même connexion) garde la même
 * pseudo-IP, donc un "ban-ip" sur un serveur fonctionne, mais la vraie IP ne
 * quitte jamais le proxy.
 *
 * Velocity n'expose pas les paquets vers les serveurs dans son API : on enveloppe
 * son initialiseur de canaux "backend" (mécanisme utilisé aussi par ViaVersion)
 * pour ajouter un gestionnaire Netty qui réécrit le Handshake avant son encodage.
 * Si ce branchement échoue (version de Velocity incompatible), install() renvoie
 * false et le plugin refuse d'enregistrer les serveurs joueurs (fail-closed).
 *
 * Lot 36 : forwarding serveur par serveur. Velocity est en mode "none" et c'est ici
 * qu'est appliqué le mode de chaque serveur (donné par l'API à l'enregistrement) :
 * legacy (Handshake réécrit, comme Velocity le ferait) ou modern (réponse au message
 * "velocity:player_info" du serveur, voir modernForwardingData). Les serveurs non
 * déclarés (le lobby) sont en legacy. Idée reprise du plugin Envoy (licence MIT).
 */
public final class IpMasker {

    private static final String HANDLER_NAME = "mcs-ip-mask";

    /** Mode de forwarding par nom de serveur Velocity : legacy, modern, none */
    private static final Map<String, String> MODES = new ConcurrentHashMap<>();

    private final Logger logger;
    private final byte[] key; // null : masquage désactivé (mask-player-ips=false)
    private ProxyServer proxy;
    private boolean perServer;

    public IpMasker(Logger logger, String key) {
        this.logger = logger;
        this.key = key == null ? null : key.getBytes(StandardCharsets.UTF_8);
    }

    public static void setMode(String server, String mode) {
        if (mode == null || mode.isBlank()) {
            MODES.remove(server);
        } else {
            MODES.put(server, mode.toLowerCase(java.util.Locale.ROOT));
        }
    }

    public static void removeMode(String server) {
        MODES.remove(server);
    }

    static String modeOf(String server) {
        return server == null ? "legacy" : MODES.getOrDefault(server, "legacy");
    }

    /** Velocity en mode "none" : le forwarding est fait ici, serveur par serveur */
    public boolean perServer() {
        return perServer;
    }

    public boolean install(ProxyServer server) {
        this.proxy = server;
        try {
            Object cfg = server.getConfiguration();
            Object mode = cfg.getClass().getMethod("getPlayerInfoForwardingMode").invoke(cfg);
            perServer = "NONE".equals(String.valueOf(mode));
            if (perServer) {
                logger.info("Forwarding serveur par serveur actif (Velocity en mode none)");
            } else {
                logger.warn("Velocity en forwarding {} : un seul mode pour tous les serveurs "
                        + "(mets player-info-forwarding-mode = \"none\" pour Fabric et Forge)", mode);
            }
        } catch (Exception e) {
            logger.warn("Mode de forwarding de Velocity illisible : {}", e.getMessage());
            perServer = false;
        }
        try {
            Field cmField = server.getClass().getDeclaredField("cm");
            cmField.setAccessible(true);
            Object connectionManager = cmField.get(server);
            Object holder = connectionManager.getClass().getMethod("getBackendChannelInitializer")
                    .invoke(connectionManager);

            @SuppressWarnings("unchecked")
            ChannelInitializer<Channel> original =
                    (ChannelInitializer<Channel>) holder.getClass().getMethod("get").invoke(holder);

            Method initChannel = ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class);
            initChannel.setAccessible(true);

            ChannelInitializer<Channel> wrapped = new ChannelInitializer<>() {
                @Override
                protected void initChannel(Channel ch) throws Exception {
                    try {
                        initChannel.invoke(original, ch);
                    } catch (InvocationTargetException e) {
                        if (e.getCause() instanceof Exception cause) {
                            throw cause;
                        }
                        throw e;
                    }
                    ch.pipeline().addLast(HANDLER_NAME, new MaskHandler());
                }
            };

            holder.getClass().getMethod("set", ChannelInitializer.class).invoke(holder, wrapped);
            if (key != null) {
                logger.info("Masquage des IP des joueurs actif (pseudo-IP envoyées aux serveurs)");
            }
            return true;
        } catch (Exception e) {
            logger.error("Impossible d'installer le masquage des IP (version de Velocity incompatible ?)", e);
            return false;
        }
    }

    /** Pseudo-IP stable dérivée de la vraie IP : 10.x.y.z (vraie IP si masquage désactivé) */
    String pseudoIp(String realIp) {
        if (key == null) {
            return realIp;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] h = mac.doFinal(realIp.getBytes(StandardCharsets.UTF_8));
            return "10." + (h[0] & 0xff) + "." + (h[1] & 0xff) + "." + (h[2] & 0xff);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC indisponible", e);
        }
    }

    /** Réécrit "hôte\0IP\0UUID\0propriétés" ; null si ce n'est pas du forwarding legacy. */
    String maskLegacyAddress(String address) {
        if (address == null) {
            return null;
        }
        String[] parts = address.split("\0", -1);
        if (parts.length < 3) {
            return null;
        }
        parts[1] = pseudoIp(parts[1]);
        return String.join("\0", parts);
    }

    /** Connexion Velocity (VelocityServerConnection) associée à un canal vers un serveur */
    private static Object serverConnection(Channel ch) {
        Object mc = ch.pipeline().get("handler");
        if (mc == null) {
            return null;
        }
        try {
            return mc.getClass().getMethod("getAssociation").invoke(mc);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Réponse au message "velocity:player_info" d'un serveur en forwarding modern
     * (FabricProxy-Lite, Proxy-Compatible-Forge, Paper), avec la pseudo-IP du joueur.
     * null si ce serveur n'est pas en modern.
     */
    public byte[] modernForwardingData(ServerConnection conn, byte[] request) throws Exception {
        if (!perServer || !"modern".equals(modeOf(conn.getServerInfo().getName()))) {
            return null;
        }
        Object cfg = proxy.getConfiguration();
        byte[] secret = (byte[]) cfg.getClass().getMethod("getForwardingSecret").invoke(cfg);
        Method addr = conn.getClass().getDeclaredMethod("getPlayerRemoteAddressAsString");
        addr.setAccessible(true);
        String address = pseudoIp((String) addr.invoke(conn));
        int version = request != null && request.length == 1 ? request[0] : 1;
        Class<?> pdf = Class.forName("com.velocitypowered.proxy.connection.PlayerDataForwarding");
        Method create = null;
        for (Method m : pdf.getDeclaredMethods()) {
            if (m.getName().equals("createForwardingData") && m.getParameterCount() == 6) {
                create = m;
            }
        }
        if (create == null) {
            throw new IllegalStateException("PlayerDataForwarding.createForwardingData introuvable");
        }
        create.setAccessible(true);
        var player = conn.getPlayer();
        Object buf = create.invoke(null, secret, address, player.getProtocolVersion(), player.getGameProfile(),
                player.getIdentifiedKey(), version);
        int readable = (int) buf.getClass().getMethod("readableBytes").invoke(buf);
        byte[] data = new byte[readable];
        buf.getClass().getMethod("readBytes", byte[].class).invoke(buf, (Object) data);
        buf.getClass().getMethod("release").invoke(buf);
        return data;
    }

    /** Un gestionnaire par connexion vers un serveur : seul le premier Handshake est concerné. */
    private final class MaskHandler extends ChannelOutboundHandlerAdapter {

        private boolean done;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (!done && msg != null && "HandshakePacket".equals(msg.getClass().getSimpleName())) {
                done = true;
                try {
                    Method get = msg.getClass().getMethod("getServerAddress");
                    Method set = msg.getClass().getMethod("setServerAddress", String.class);
                    String address = (String) get.invoke(msg);
                    if (perServer) {
                        // Velocity en "none" : adresse simple (+ marqueur Forge). Forwarding
                        // legacy construit ici pour les serveurs qui le demandent.
                        Object conn = serverConnection(ctx.channel());
                        String mode = modeOf(conn instanceof ServerConnection sc ? sc.getServerInfo().getName() : null);
                        if ("legacy".equals(mode)) {
                            if (conn == null) {
                                throw new IllegalStateException("connexion au serveur introuvable");
                            }
                            Method legacy = conn.getClass().getDeclaredMethod("createLegacyForwardingAddress");
                            legacy.setAccessible(true);
                            set.invoke(msg, maskLegacyAddress((String) legacy.invoke(conn)));
                        }
                    } else {
                        String masked = maskLegacyAddress(address);
                        if (masked != null) {
                            set.invoke(msg, masked);
                        }
                    }
                } catch (Exception e) {
                    // Fail-closed : jamais d'IP réelle envoyée si la réécriture échoue
                    logger.error("Masquage de l'IP impossible, connexion au serveur annulée", e);
                    promise.setFailure(e);
                    ctx.close();
                    return;
                }
            }
            super.write(ctx, msg, promise);
        }
    }
}
