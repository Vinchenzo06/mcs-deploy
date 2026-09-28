package vinch.mcs.proxymanager;

import com.velocitypowered.api.proxy.ProxyServer;
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
 */
public final class IpMasker {

    private static final String HANDLER_NAME = "mcs-ip-mask";

    private final Logger logger;
    private final byte[] key;

    public IpMasker(Logger logger, String key) {
        this.logger = logger;
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    public boolean install(ProxyServer server) {
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
            logger.info("Masquage des IP des joueurs actif (pseudo-IP envoyées aux serveurs)");
            return true;
        } catch (Exception e) {
            logger.error("Impossible d'installer le masquage des IP (version de Velocity incompatible ?)", e);
            return false;
        }
    }

    /** Pseudo-IP stable dérivée de la vraie IP : 10.x.y.z */
    String pseudoIp(String realIp) {
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
                    String masked = maskLegacyAddress((String) get.invoke(msg));
                    if (masked != null) {
                        set.invoke(msg, masked);
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
