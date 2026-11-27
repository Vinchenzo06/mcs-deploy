package vinch.mcs.api.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vinch.mcs.api.entities.ServerType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lot 76 : description des serveurs à la demande (pools.json) */
class PoolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void livre() throws Exception {
        // Le fichier livré dans deploy/pools (les tests tournent depuis api/)
        Path file = Path.of("..", "deploy", "pools", "pools.json");
        Pools.Config c = Pools.parse(JSON.readTree(Files.readAllBytes(file)));
        assertNull(c.owner());
        assertEquals(List.of("worldreset", "bingo", "creatif"), c.pools().stream().map(Pools.Pool::name).toList());
        assertEquals("bingo", c.pools().get(1).server());
        assertEquals(0, c.pools().get(1).keep());
        assertEquals(0, c.pools().get(1).idleStopMinutes());
        Pools.Pool s = c.pools().get(0);
        assertEquals(ServerType.PAPER, s.type());
        assertEquals(1, s.keep());
        assertEquals(3, s.maxExtra());
        assertEquals(24, s.idleDeleteHours());
        assertTrue(s.modrinth().contains("worldreset"));
    }

    @Test
    void valeursParDefautEtGroupesInvalides() throws Exception {
        Pools.Config c = Pools.parse(JSON.readTree("""
                {"owner": "Vinch", "pools": [
                  {"name": "Mauvais Nom"},
                  {"name": "bingo", "type": "inconnu"},
                  {"name": "fixe", "server": "../x"},
                  {"name": "creatif", "prefix": "../x", "ramMb": 10, "keep": -2, "idleDeleteHours": 0}
                ]}"""));
        assertEquals("Vinch", c.owner());
        assertEquals(1, c.pools().size());
        Pools.Pool p = c.pools().get(0);
        assertEquals("creatif", p.prefix());
        assertNull(p.server());
        assertEquals(1024, p.ramMb());
        assertEquals(0, p.keep());
        assertEquals(3, p.maxExtra());
        assertEquals(1, p.idleDeleteHours());
        assertEquals(15, p.idleStopMinutes());
        assertEquals(ServerType.PAPER, p.type());
    }
}
