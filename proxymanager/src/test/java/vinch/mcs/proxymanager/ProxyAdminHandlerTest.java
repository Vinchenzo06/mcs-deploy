package vinch.mcs.proxymanager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Lot 73 : jamais d'adresse IP dans le journal ou la console montrés sur le site (lot 70c) */
class ProxyAdminHandlerTest {

    @Test
    void ipv4IsMasked() {
        String line = "[12:34:56 INFO]: [connected player] Vinch (/203.0.113.7:51234) has connected";
        assertEquals("[12:34:56 INFO]: [connected player] Vinch (/x.x.x.x:51234) has connected",
                ProxyAdminHandler.maskIps(line));
    }

    @Test
    void ipv6IsMasked() {
        String masked = ProxyAdminHandler.maskIps("Bob (/[2001:db8::1]:5000) has connected");
        assertFalse(masked.contains("2001"), masked);
        assertEquals("Bob (/[ip]:5000) has connected", masked);
    }

    @Test
    void timesAndVersionsAreKept() {
        String line = "[12:34:57 INFO]: Loaded plugin viaversion 5.12.1 by kennytv (Velocity 4.3.0)";
        assertEquals(line, ProxyAdminHandler.maskIps(line));
    }
}
