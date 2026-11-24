package vinch.mcs.api.services;

import org.junit.jupiter.api.Test;
import vinch.mcs.api.entities.Node;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Lot 73 : mémoire Java et quotas disque des serveurs */
class ServerSizingTest {

    @Test
    void javaHeapLeavesRoomForTheJvm() {
        // Même calcul que l'agent (agent/files_test.go)
        assertEquals(256, ServerService.javaHeapMb(600));
        assertEquals(512, ServerService.javaHeapMb(1024));
        assertEquals(1536, ServerService.javaHeapMb(2048));
        assertEquals(3277, ServerService.javaHeapMb(4096));
        assertEquals(6554, ServerService.javaHeapMb(8192));
        assertEquals(16928, ServerService.javaHeapMb(20000));
    }

    @Test
    void diskQuotaFollowsTheShareOfRam() {
        Node n = new Node();
        n.setTotalRamMb(16384);
        n.setTotalStorageMb(100_000);
        assertEquals(25_000, ServerService.quotaOn(n, 4096));
        assertEquals(1024, ServerService.quotaOn(n, 64));       // plancher
        assertEquals(100_000, ServerService.quotaOn(n, 32768)); // jamais plus que le disque prêté
        n.setTotalRamMb(0);
        assertEquals(5000, ServerService.quotaOn(n, 4096));     // capacité inconnue
    }
}
