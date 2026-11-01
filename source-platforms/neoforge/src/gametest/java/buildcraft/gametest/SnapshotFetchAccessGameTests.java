/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.gametest;

import java.util.BitSet;
import java.util.Date;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.SnapshotFetchAccess;
import buildcraft.builders.snapshot.Template;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.enums.EnumSnapshotType;
import buildcraft.lib.misc.FakePlayerProvider;

/**
 * MessageSnapshotRequest used to send any stored blueprint to any player who named its content hash. Only the owner,
 * an operator or a player holding a blueprint item for that content may fetch it now.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SnapshotFetchAccessGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";
    private static final UUID OWNER_ID = UUID.fromString("7f2e0c5a-3d4b-4e8f-a01b-2c3d4e5f6071");
    private static final GameProfile OWNER = new GameProfile(OWNER_ID, "BCFetchOwner");
    private static final GameProfile STRANGER =
        new GameProfile(UUID.fromString("803f1d6b-4e5c-4f90-b12c-3d4e5f607182"), "BCFetchStranger");

    private SnapshotFetchAccessGameTests() {
    }

    private static Template ownedTemplate(int bit) {
        Template template = new Template();
        template.size = new BlockPos(4, 1, 1);
        template.offset = BlockPos.ZERO;
        template.facing = Direction.NORTH;
        template.data = new BitSet(4);
        template.data.set(bit);
        template.computeKey();
        Snapshot.Header header = new Snapshot.Header(template.key, OWNER_ID, new Date(), "fetch-access-" + bit);
        template.key = new Snapshot.Key(template.key, header);
        return template;
    }

    private static ServerPlayer player(GameTestHelper helper, GameProfile profile) {
        ServerPlayer player = FakePlayerProvider.INSTANCE.getFakePlayer(helper.getLevel(), profile);
        player.getInventory().clearContent();
        player.containerMenu = player.inventoryMenu;
        player.containerMenu.setCarried(ItemStack.EMPTY);
        return player;
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void onlyOwnerOrHolderMayFetchABlueprint(GameTestHelper helper) {
        Template stored = ownedTemplate(1);
        Template other = ownedTemplate(2);
        ServerPlayer owner = player(helper, OWNER);
        ServerPlayer stranger = player(helper, STRANGER);
        try {
            // Fake players have no operator permission, so only the owner/holder rules apply here.
            if (SnapshotFetchAccess.canFetch(stranger, stored)) {
                helper.fail("A player who only knows the content hash could fetch the blueprint");
                return;
            }
            if (!SnapshotFetchAccess.canFetch(owner, stored)) {
                helper.fail("The owner could not fetch their own blueprint");
                return;
            }
            // A written item for other content does not open this one.
            stranger.getInventory().setItem(0, ItemSnapshot.getUsed(EnumSnapshotType.TEMPLATE, other.key.header));
            if (SnapshotFetchAccess.canFetch(stranger, stored)) {
                helper.fail("A blueprint item for different content granted access");
                return;
            }
            // Holding the item (e.g. given by the owner) is what the tooltip/Replacer requests need.
            stranger.getInventory().setItem(1, ItemSnapshot.getUsed(EnumSnapshotType.TEMPLATE, stored.key.header));
            if (!SnapshotFetchAccess.canFetch(stranger, stored)) {
                helper.fail("A player holding the blueprint item could not fetch it");
                return;
            }
            stranger.getInventory().clearContent();
            stranger.containerMenu.setCarried(ItemSnapshot.getUsed(EnumSnapshotType.TEMPLATE, stored.key.header));
            if (!SnapshotFetchAccess.canFetch(stranger, stored)) {
                helper.fail("A blueprint item on the cursor did not grant access");
                return;
            }
            stranger.containerMenu.setCarried(ItemStack.EMPTY);
            // A stored copy without a header (older saves) is still not open to everyone.
            Template headerless = ownedTemplate(1);
            headerless.key = new Snapshot.Key(headerless.key, (Snapshot.Header) null);
            if (SnapshotFetchAccess.canFetch(stranger, headerless)) {
                helper.fail("A headerless stored blueprint was open to everyone");
                return;
            }
        } catch (RuntimeException e) {
            helper.fail("Test failed: " + e);
            return;
        } finally {
            owner.getInventory().clearContent();
            stranger.getInventory().clearContent();
            stranger.containerMenu.setCarried(ItemStack.EMPTY);
        }
        helper.succeed();
    }
}
