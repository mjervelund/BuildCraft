/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.gametest;

import java.lang.reflect.Field;
import java.util.BitSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.tile.TileArchitectTable;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.enums.EnumSnapshotType;

@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class ArchitectTableGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";

    private ArchitectTableGameTests() {
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = TileArchitectTable.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /**
     * After a scan whose blueprint could not be saved, the table keeps its scanned arrays but has no iterator
     * ({@code boxIterator} is cleared when the scan finishes). The next tick starts a new scan, which used to throw
     * "this.boxIterator is null" from the block entity tick and crash the server.
     */
    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void scanRestartsAfterAFailedSave(GameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, BCBuildersBlocks.ARCHITECT.get().defaultBlockState());
        BlockEntity blockEntity = GameTestCompat.getBlockEntity(helper, relative);
        if (!(blockEntity instanceof TileArchitectTable tile)) {
            helper.fail("Architect block did not create TileArchitectTable");
            return;
        }
        // 20x10x20 = 4000 blocks: more than the two ticks of template scanning (2 x 900) below, so the scan
        // cannot finish (and try to save) inside this test.
        BlockPos min = helper.absolutePos(BlockPos.ZERO);
        tile.box.reset();
        tile.box.setMin(min);
        tile.box.setMax(min.offset(19, 9, 19));
        tile.invSnapshotIn.setStackInSlot(0, ItemSnapshot.getClean(EnumSnapshotType.TEMPLATE));
        int dataSize = Snapshot.getDataSize(tile.box.size());
        try {
            field("isValid").setBoolean(tile, true);
            field("scanning").setBoolean(tile, false);
            field("boxIterator").set(tile, null);
            field("templateScannedBlocks").set(tile, new BitSet(dataSize));
            field("blueprintScannedData").set(tile, new int[dataSize]);
            @SuppressWarnings("unchecked")
            List<Object> entities = (List<Object>) field("blueprintScannedEntities").get(tile);
            entities.add(null);
        } catch (ReflectiveOperationException e) {
            helper.fail("Test setup failed: " + e);
            return;
        }

        try {
            tile.update();
            tile.update();
        } catch (RuntimeException failure) {
            helper.fail("Architect Table tick after a failed save threw " + failure);
            return;
        }

        try {
            if (!field("scanning").getBoolean(tile) || field("boxIterator").get(tile) == null) {
                helper.fail("Architect Table did not start a new scan after a failed save");
                return;
            }
            if (!((List<?>) field("blueprintScannedEntities").get(tile)).isEmpty()) {
                helper.fail("Entities from the failed scan were kept for the new scan");
                return;
            }
        } catch (ReflectiveOperationException e) {
            helper.fail("Test check failed: " + e);
            return;
        }
        helper.succeed();
    }
}
