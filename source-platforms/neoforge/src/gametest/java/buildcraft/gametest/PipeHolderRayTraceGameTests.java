/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.gametest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.lib.BCLib;
import buildcraft.transport.BCTransportBlocks;
import buildcraft.transport.block.BlockPipeHolder;

/**
 * BlockPipeHolder.rayTrace(world, pos, start, end) dereferenced the pipe block entity without a null check. The block
 * state can be visible while its block entity is not (chunk transitions; a server Level returns no block entity to
 * other threads), and the ray trace then threw a NullPointerException.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class PipeHolderRayTraceGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";

    private PipeHolderRayTraceGameTests() {
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void pipeRayTraceWithoutBlockEntityDoesNotThrow(GameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, BCTransportBlocks.pipeHolder.get().defaultBlockState());
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        BlockPipeHolder block = (BlockPipeHolder) level.getBlockState(pos).getBlock();
        Vec3 start = Vec3.atCenterOf(pos).add(0, 2, 0);
        Vec3 end = Vec3.atCenterOf(pos).subtract(0, 2, 0);

        // Same thread: the block entity exists and the trace hits the pipe.
        if (block.rayTrace(level, pos, start, end) == null) {
            helper.fail("ray trace through a pipe with its block entity returned null");
            return;
        }

        // Another thread sees the block state but no block entity (ServerLevel.getBlockEntity off-thread is null).
        try {
            CompletableFuture.runAsync(() -> {
                if (BlockPipeHolder.getPipe(level, pos, false) != null) {
                    throw new IllegalStateException("precondition: block entity visible off-thread");
                }
                block.rayTrace(level, pos, start, end);
            }).get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            helper.fail("ray trace of a pipe state without block entity threw " + e.getCause());
            return;
        } catch (TimeoutException e) {
            helper.fail("off-thread ray trace did not finish");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            helper.fail("interrupted");
            return;
        }
        helper.succeed();
    }
}
