/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.gametest;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.block.BlockArchitectTable;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.Template;
import buildcraft.builders.tile.TileArchitectTable;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.enums.EnumSnapshotType;

@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class ArchitectTableSaveGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";

    private ArchitectTableSaveGameTests() {
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = TileArchitectTable.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var children = Files.list(path)) {
                for (Path child : children.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    /**
     * A blueprint that cannot be saved is reported once; the table then waits for a player to change the blueprint
     * slots instead of rescanning and retrying the save every tick.
     */
    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void failedSaveIsNotRetriedEveryTick(GameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, BCBuildersBlocks.ARCHITECT.get().defaultBlockState());
        BlockEntity blockEntity = GameTestCompat.getBlockEntity(helper, relative);
        if (!(blockEntity instanceof TileArchitectTable tile)) {
            helper.fail("Architect block did not create TileArchitectTable");
            return;
        }
        BlockPos tablePos = helper.absolutePos(relative);
        BlockPos min = helper.absolutePos(new BlockPos(0, 2, 0));
        tile.box.reset();
        tile.box.setMin(min);
        tile.box.setMax(min.offset(2, 0, 2));

        // The template the scan will produce, so the test knows the file name it will be saved under.
        Direction facing = helper.getLevel().getBlockState(tablePos).getValue(BlockArchitectTable.PROP_FACING);
        Template expected = new Template();
        expected.size = tile.box.size();
        expected.facing = facing;
        expected.offset = min.subtract(tablePos.relative(facing.getOpposite()));
        expected.data = new BitSet(Snapshot.getDataSize(expected.size));
        for (int x = 0; x < expected.size.getX(); x++) {
            for (int y = 0; y < expected.size.getY(); y++) {
                for (int z = 0; z < expected.size.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    expected.data.set(Snapshot.posToIndex(expected.size, pos),
                        !helper.getLevel().isEmptyBlock(min.offset(pos)));
                }
            }
        }
        expected.computeKey();

        // A non-empty directory where the blueprint file goes makes the final rename fail.
        Path target = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
            .resolve("buildcraft/blueprints/server/" + expected.key + ".bcnbt");
        try {
            deleteRecursively(target);
            Files.createDirectories(target.resolve("blocker"));
            field("isValid").setBoolean(tile, true);
        } catch (IOException | ReflectiveOperationException e) {
            helper.fail("Test setup failed: " + e);
            return;
        }
        tile.invSnapshotIn.setStackInSlot(0, ItemSnapshot.getClean(EnumSnapshotType.TEMPLATE));

        try {
            tile.update(); // scans the 9 blocks and fails to save
            if (!tile.invSnapshotOut.getStackInSlot(0).isEmpty() || tile.invSnapshotIn.getStackInSlot(0).isEmpty()) {
                helper.fail("The save was expected to fail and keep the blank blueprint");
                return;
            }
            for (int i = 0; i < 3; i++) {
                tile.update();
                if (field("scanning").getBoolean(tile)) {
                    helper.fail("The table started a new scan right after a failed save");
                    return;
                }
            }

            if (!field("saveFailed").getBoolean(tile)) {
                helper.fail("The failed save was not recorded");
                return;
            }

            // A player takes the blueprint out and puts it back: the table may try again.
            deleteRecursively(target);
            ItemStack blank = tile.invSnapshotIn.getStackInSlot(0);
            tile.invSnapshotIn.setStackInSlot(0, ItemStack.EMPTY);
            tile.invSnapshotIn.setStackInSlot(0, blank);
            if (field("saveFailed").getBoolean(tile)) {
                helper.fail("Changing the blueprint slots did not allow a new attempt");
                return;
            }
        } catch (RuntimeException failure) {
            helper.fail("Architect Table tick threw " + failure);
            return;
        } catch (IOException | ReflectiveOperationException e) {
            helper.fail("Test step failed: " + e);
            return;
        } finally {
            // Only this tick's calls are under test; don't leave a table that ticks on.
            helper.setBlock(relative, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }
}
