package vinch.mcs.api.services;

import org.junit.jupiter.api.Test;
import vinch.mcs.api.entities.Backup;
import vinch.mcs.api.entities.BackupKind;
import vinch.mcs.api.services.BackupService.Rule;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lot 73 : rythme des sauvegardes automatiques (dueKinds) et rotation (retained) */
class BackupRulesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 12, 0);

    private static Map<BackupKind, Rule> rules(int daily, int weekly, int monthly) {
        Map<BackupKind, Rule> m = new EnumMap<>(BackupKind.class);
        m.put(BackupKind.DAILY, new Rule(daily, 7));
        m.put(BackupKind.WEEKLY, new Rule(weekly, 28));
        m.put(BackupKind.MONTHLY, new Rule(monthly, 90));
        m.put(BackupKind.MANUAL, new Rule(2, 30));
        m.put(BackupKind.PERMANENT, new Rule(1, 0));
        return m;
    }

    private static Backup b(long id, LocalDateTime at, BackupKind... kinds) {
        Set<BackupKind> k = kinds.length == 0 ? EnumSet.noneOf(BackupKind.class) : EnumSet.of(kinds[0], kinds);
        return Backup.builder().id(id).createdAt(at).status("SUCCESS")
                .isDaily(k.contains(BackupKind.DAILY)).isWeekly(k.contains(BackupKind.WEEKLY))
                .isMonthly(k.contains(BackupKind.MONTHLY)).isManual(k.contains(BackupKind.MANUAL))
                .isPermanent(k.contains(BackupKind.PERMANENT)).build();
    }

    @Test
    void newServerGetsItsFirstBackupWithinAboutAnHour() {
        assertEquals(Set.of(), BackupService.dueKinds(rules(7, 4, 3), List.of(), NOW.minusMinutes(30), NOW));
        assertEquals(EnumSet.of(BackupKind.DAILY, BackupKind.WEEKLY, BackupKind.MONTHLY),
                BackupService.dueKinds(rules(7, 4, 3), List.of(), NOW.minusHours(2), NOW));
    }

    @Test
    void noSecondDailyTheSameDay() {
        List<Backup> ok = List.of(b(1, NOW.minusHours(2), BackupKind.DAILY, BackupKind.WEEKLY, BackupKind.MONTHLY));
        assertEquals(Set.of(), BackupService.dueKinds(rules(7, 4, 3), ok, NOW.minusDays(30), NOW));
    }

    @Test
    void weeklyRidesOnTheDaily() {
        List<Backup> ok = List.of(
                b(3, NOW.minusHours(25), BackupKind.DAILY),
                b(2, NOW.minusDays(8), BackupKind.DAILY, BackupKind.WEEKLY),
                b(1, NOW.minusDays(10), BackupKind.DAILY, BackupKind.MONTHLY));
        assertEquals(EnumSet.of(BackupKind.DAILY, BackupKind.WEEKLY),
                BackupService.dueKinds(rules(7, 4, 3), ok, NOW.minusDays(60), NOW));
    }

    @Test
    void withoutDailyOnlyWeeklyAndMonthly() {
        List<Backup> old = List.of(b(1, NOW.minusDays(8), BackupKind.WEEKLY), b(0, NOW.minusDays(9), BackupKind.MONTHLY));
        assertEquals(EnumSet.of(BackupKind.WEEKLY), BackupService.dueKinds(rules(0, 4, 3), old, NOW.minusDays(60), NOW));
        List<Backup> recent = List.of(b(1, NOW.minusDays(2), BackupKind.WEEKLY), b(0, NOW.minusDays(5), BackupKind.MONTHLY));
        assertEquals(Set.of(), BackupService.dueKinds(rules(0, 4, 3), recent, NOW.minusDays(60), NOW));
    }

    @Test
    void rotationKeepsTheNewestAndDropsExpired() {
        List<Backup> ok = List.of(
                b(4, NOW.minusHours(1), BackupKind.DAILY),
                b(3, NOW.minusDays(1), BackupKind.DAILY),
                b(2, NOW.minusDays(2), BackupKind.DAILY),
                b(1, NOW.minusDays(10), BackupKind.MANUAL));
        Map<BackupKind, Rule> r = rules(2, 4, 3);
        assertEquals(Set.of(4L, 3L, 1L), BackupService.retained(ok, r, NOW, false));
        r.put(BackupKind.MANUAL, new Rule(2, 7)); // manuelle de 10 jours, durée 7 jours : expirée
        assertEquals(Set.of(4L, 3L), BackupService.retained(ok, r, NOW, false));
    }

    @Test
    void permanentTakesNoPlaceAndGoesWithADeletedServer() {
        List<Backup> ok = List.of(
                b(3, NOW.minusHours(1), BackupKind.DAILY, BackupKind.PERMANENT),
                b(2, NOW.minusDays(1), BackupKind.DAILY),
                b(1, NOW.minusDays(2), BackupKind.DAILY));
        Set<Long> kept = BackupService.retained(ok, rules(1, 4, 3), NOW, false);
        assertTrue(kept.contains(3L), "permanente gardée");
        assertTrue(kept.contains(2L), "la permanente ne prend pas la place de la quotidienne");
        assertEquals(2, kept.size());
        assertEquals(Set.of(2L), BackupService.retained(ok, rules(1, 4, 3), NOW, true));
    }
}
