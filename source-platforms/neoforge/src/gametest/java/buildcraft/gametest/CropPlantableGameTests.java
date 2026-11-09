/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.lib.BCLib;
import buildcraft.lib.crops.CropHandlerPlantable;
import buildcraft.lib.misc.FakePlayerProvider;

/**
 * The planter robot plants through {@link CropHandlerPlantable}: saplings and vanilla seeds must count as seeds and be
 * planted, and a plant standing on the same plant counts as grown (BCCE-team/BuildCraft#74).
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class CropPlantableGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";

    private CropPlantableGameTests() {
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void plantableHandlerPlantsSaplingsAndSeeds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CropHandlerPlantable handler = CropHandlerPlantable.INSTANCE;
        ItemStack sapling = new ItemStack(Items.OAK_SAPLING);
        ItemStack seeds = new ItemStack(Items.WHEAT_SEEDS);

        require(helper, handler.isSeed(sapling), "oak sapling is not a seed for the plantable crop handler");
        require(helper, handler.isSeed(seeds), "wheat seeds are not a seed for the plantable crop handler");
        require(helper, !handler.isSeed(new ItemStack(Items.SUGAR_CANE)), "sugar cane must stay with the reeds handler");
        require(helper, !handler.isSeed(new ItemStack(Items.STONE)), "stone is a seed for the plantable crop handler");

        BlockPos dirt = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos farmland = helper.absolutePos(new BlockPos(2, 0, 0));
        helper.setBlock(new BlockPos(0, 0, 0), Blocks.DIRT.defaultBlockState());
        helper.setBlock(new BlockPos(2, 0, 0), Blocks.FARMLAND.defaultBlockState());

        require(helper, handler.canSustainPlant(level, sapling, dirt), "dirt cannot sustain an oak sapling");
        require(helper, handler.canSustainPlant(level, seeds, farmland), "farmland cannot sustain wheat seeds");
        require(helper, !handler.canSustainPlant(level, seeds, dirt), "plain dirt sustains wheat seeds");

        Player player = FakePlayerProvider.INSTANCE.getBuildCraftPlayer(level);
        require(helper, handler.plant(level, player, sapling.copy(), dirt), "planting the oak sapling failed");
        require(helper, level.getBlockState(dirt.above()).is(Blocks.OAK_SAPLING), "no oak sapling above the dirt");
        require(helper, handler.plant(level, player, seeds.copy(), farmland), "planting the wheat seeds failed");
        require(helper, level.getBlockState(farmland.above()).is(Blocks.WHEAT), "no wheat above the farmland");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void plantableHandlerTreatsStackedPlantAsMature(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CropHandlerPlantable handler = CropHandlerPlantable.INSTANCE;
        BlockPos lower = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos upper = lower.above();
        helper.setBlock(new BlockPos(1, 0, 1), Blocks.DIRT.defaultBlockState());
        level.setBlock(lower, Blocks.OAK_SAPLING.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        // Without shape updates so the upper sapling stays even though it could not survive there.
        level.setBlock(upper, Blocks.OAK_SAPLING.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);

        require(helper, level.getBlockState(upper).is(Blocks.OAK_SAPLING), "upper oak sapling was not kept");
        require(helper, !handler.isMature(level, level.getBlockState(lower), lower),
            "an oak sapling on dirt counts as mature");
        require(helper, handler.isMature(level, level.getBlockState(upper), upper),
            "an oak sapling on an oak sapling does not count as mature");
        helper.succeed();
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
