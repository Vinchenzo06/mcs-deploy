package vinch.mcs.api.services;

import org.junit.jupiter.api.Test;
import vinch.mcs.api.entities.Server;
import vinch.mcs.api.entities.ServerType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Lot 73 : version de Java choisie pour chaque type et version de Minecraft (lot 35) */
class JavaVersionsTest {

    @Test
    void paperAndSpigot() {
        assertEquals(8, JavaVersions.auto(ServerType.PAPER, "1.8.8"));
        assertEquals(11, JavaVersions.auto(ServerType.PAPER, "1.12.2"));
        assertEquals(11, JavaVersions.auto(ServerType.SPIGOT, "1.16.4"));
        assertEquals(16, JavaVersions.auto(ServerType.PAPER, "1.16.5"));
        assertEquals(17, JavaVersions.auto(ServerType.PAPER, "1.18.2"));
        assertEquals(21, JavaVersions.auto(ServerType.PAPER, "1.20.4"));
        assertEquals(21, JavaVersions.auto(ServerType.PAPER, "1.21.4"));
    }

    @Test
    void forgeFabricVanilla() {
        assertEquals(8, JavaVersions.auto(ServerType.FORGE, "1.16.5"));
        assertEquals(16, JavaVersions.auto(ServerType.FORGE, "1.17.1"));
        assertEquals(17, JavaVersions.auto(ServerType.FORGE, "1.20.4"));
        assertEquals(21, JavaVersions.auto(ServerType.NEOFORGE, "1.20.6"));
        assertEquals(8, JavaVersions.auto(ServerType.FABRIC, "1.16.5"));
        assertEquals(17, JavaVersions.auto(ServerType.FABRIC, "1.19.4"));
        assertEquals(21, JavaVersions.auto(ServerType.VANILLA, "1.21"));
    }

    @Test
    void newestForRecentAndUnknown() {
        assertEquals(JavaVersions.NEWEST, JavaVersions.auto(ServerType.PAPER, "26.3"));
        assertEquals(JavaVersions.NEWEST, JavaVersions.auto(ServerType.FABRIC, "26.1.2"));
        assertEquals(JavaVersions.NEWEST, JavaVersions.auto(ServerType.PAPER, "LATEST"));
        assertEquals(JavaVersions.NEWEST, JavaVersions.auto(null, null));
    }

    @Test
    void ownerChoice() {
        assertEquals(17, JavaVersions.parseChoice("java 17"));
        assertEquals(21, JavaVersions.parseChoice(" 21 "));
        assertNull(JavaVersions.parseChoice("auto"));
        assertNull(JavaVersions.parseChoice(""));
        assertThrows(RuntimeException.class, () -> JavaVersions.parseChoice("9"));
        assertThrows(RuntimeException.class, () -> JavaVersions.parseChoice("abc"));

        Server s = Server.builder().serverType(ServerType.PAPER).minecraftVersion("1.12.2").build();
        assertEquals(11, JavaVersions.effective(s));
        s.setJavaVersion(17);
        assertEquals(17, JavaVersions.effective(s));
        s.setJavaVersion(9); // inconnue : automatique
        assertEquals(11, JavaVersions.effective(s));
    }

    @Test
    void suggestionAfterAgentDiagnosis() {
        Server s = Server.builder().serverType(ServerType.PAPER).minecraftVersion("1.20.4").build();
        assertEquals(21, JavaVersions.suggest(21, "newer", s, 17));
        assertEquals(17, JavaVersions.suggest(17, "older", s, 21));
        assertEquals(25, JavaVersions.suggest(25, "", s, 21));
    }
}
