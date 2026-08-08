package dev.astock.paper.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferJournalTest {
    @TempDir Path directory;

    @Test
    void persistsCrashRecoveryBoundaryAtomically() throws Exception {
        Path file = directory.resolve("transfer-journal.json");
        UUID player = UUID.randomUUID();
        var journal = new TransferJournal(file);
        journal.put(new TransferJournal.Entry("transfer-1", "request-1", player, "DEPOSIT", 12_345,
                TransferJournal.Phase.SERVICE_BEGUN, Instant.now().toEpochMilli()));
        journal.mark("transfer-1", TransferJournal.Phase.ECONOMY_MUTATED);

        var recovered = new TransferJournal(file).snapshot();
        assertThat(recovered).singleElement().satisfies(entry -> {
            assertThat(entry.transferId()).isEqualTo("transfer-1");
            assertThat(entry.playerUuid()).isEqualTo(player);
            assertThat(entry.phase()).isEqualTo(TransferJournal.Phase.ECONOMY_MUTATED);
        });

        var reopened = new TransferJournal(file);
        reopened.remove("transfer-1");
        assertThat(new TransferJournal(file).snapshot()).isEmpty();
    }
}
