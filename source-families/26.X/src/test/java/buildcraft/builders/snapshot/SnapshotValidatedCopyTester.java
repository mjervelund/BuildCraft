package buildcraft.builders.snapshot;

import java.util.BitSet;
import java.util.Date;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * GlobalSavedDataSnapshots.validatedCopy guards every blueprint store write and read (client library, server store,
 * Electronic Library upload cache). It compared a recomputed key with itself (Snapshot.copy() already recomputes the
 * key), so a snapshot whose content does not match its key passed. It now compares with the key the snapshot came with.
 */
class SnapshotValidatedCopyTester {
    private static Template template(int... filled) {
        Template template = new Template();
        template.size = new BlockPos(2, 2, 2);
        template.facing = Direction.NORTH;
        template.offset = BlockPos.ZERO;
        template.data = new BitSet(template.getDataSize());
        for (int index : filled) {
            template.data.set(index);
        }
        template.computeKey();
        return template;
    }

    @Test
    void honestSnapshotPassesWithItsHash() {
        Template honest = template(0, 3, 5);
        Snapshot checked = GlobalSavedDataSnapshots.validatedCopy(honest);
        Assertions.assertNotNull(checked, "an honest snapshot was refused");
        Assertions.assertArrayEquals(honest.key.hash, checked.key.hash);
    }

    @Test
    void honestSnapshotSurvivesNbtRoundTrip() throws Exception {
        Template honest = template(1, 2, 7);
        CompoundTag nbt = Snapshot.writeToNBT(honest);
        Snapshot read = Snapshot.readFromNBT(nbt.copy());
        Snapshot checked = GlobalSavedDataSnapshots.validatedCopy(read);
        Assertions.assertNotNull(checked, "a snapshot read back from NBT was refused (hash not stable)");
        Assertions.assertArrayEquals(honest.key.hash, checked.key.hash);
    }

    @Test
    void contentThatDoesNotMatchItsKeyIsRefused() {
        Template tampered = template(0, 3, 5);
        tampered.data.set(6); // content changed after the key was computed (edited file, forged upload)
        Assertions.assertNull(GlobalSavedDataSnapshots.validatedCopy(tampered),
            "a snapshot whose content does not match its key was accepted");
    }

    @Test
    void foreignKeyIsRefused() {
        Template a = template(0);
        Template b = template(7);
        Snapshot.Header header = new Snapshot.Header(b.key, UUID.randomUUID(), "maker", new Date(), "b");
        a.key = new Snapshot.Key(b.key, header); // a's content under b's key and name
        Assertions.assertNull(GlobalSavedDataSnapshots.validatedCopy(a), "content was accepted under another key");
    }

    @Test
    void missingHashIsRefused() {
        Template keyless = template(2);
        keyless.key = new Snapshot.Key();
        Assertions.assertNull(GlobalSavedDataSnapshots.validatedCopy(keyless));
    }
}
