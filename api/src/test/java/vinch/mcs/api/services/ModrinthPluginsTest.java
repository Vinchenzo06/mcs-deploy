package vinch.mcs.api.services;

import org.junit.jupiter.api.Test;
import vinch.mcs.api.entities.Server;
import vinch.mcs.api.entities.ServerType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Lot 73 : liste Modrinth d'un serveur (lot 70) */
class ModrinthPluginsTest {

    @Test
    void normalize() {
        assertEquals(List.of("luckperms", "chunky"), ModrinthPlugins.normalize(List.of(" luckperms", "chunky", "luckperms", "")));
        assertThrows(IllegalArgumentException.class, () -> ModrinthPlugins.normalize(List.of("../x")));
        assertThrows(IllegalArgumentException.class, () -> ModrinthPlugins.normalize(List.of("a,b")));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < ModrinthPlugins.MAX + 1; i++) {
            many.add("p" + i);
        }
        assertThrows(IllegalArgumentException.class, () -> ModrinthPlugins.normalize(many));
    }

    @Test
    void sentToTheAgent() {
        Server paper = Server.builder().serverType(ServerType.PAPER).modrinthProjects("luckperms,chunky").build();
        Map<String, Object> data = new HashMap<>();
        ModrinthPlugins.apply(data, paper);
        assertEquals(List.of("luckperms", "chunky"), data.get("modrinth_projects"));
        assertEquals("plugins", ModrinthPlugins.kind(ServerType.PAPER));
        assertEquals("mods", ModrinthPlugins.kind(ServerType.FABRIC));

        Server vanilla = Server.builder().serverType(ServerType.VANILLA).modrinthProjects("x1").build();
        ModrinthPlugins.apply(data, vanilla);
        assertEquals(List.of(), data.get("modrinth_projects"));
    }
}
