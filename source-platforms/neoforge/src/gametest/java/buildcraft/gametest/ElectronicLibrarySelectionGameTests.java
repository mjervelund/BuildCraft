/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.gametest;

import java.util.Date;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import io.netty.buffer.Unpooled;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.menu.ContainerElectronicLibrary;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.tile.TileElectronicLibrary;
import buildcraft.lib.BCLib;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

/**
 * Two players at one Electronic Library: each one's selection is their own. The library's selection used to be one
 * field on the tile, so a click by one player changed the other's selection, and the upload request went to every
 * viewer. Now the upload works on the latest player's selection, is requested from that player only, and only that
 * player's answer is accepted; clearing a selection does not clear another player's.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class ElectronicLibrarySelectionGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";
    private static final GameProfile PLAYER_A =
        new GameProfile(UUID.fromString("7f2e0c50-3d4b-4e8f-a01b-2c3d4e5f6071"), "BCSelectA");
    private static final GameProfile PLAYER_B =
        new GameProfile(UUID.fromString("80f31d61-4e5c-4f90-b12c-3d4e5f607182"), "BCSelectB");

    private ElectronicLibrarySelectionGameTests() {
    }

    private static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    private static void require(boolean condition, String message) throws Failure {
        if (!condition) {
            throw new Failure(message);
        }
    }

    private static BCPacketContext serverContext(ServerPlayer player) {
        return new BCPacketContext() {
            @Override
            public BCNetworkSide side() {
                return BCNetworkSide.SERVER;
            }

            @Override
            public Player player() {
                return player;
            }

            @Override
            public void enqueueWork(Runnable task) {
                task.run();
            }

            @Override
            public void setPacketHandled(boolean handled) {
            }
        };
    }

    private static Snapshot.Key key(String name, int seed) {
        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (seed * 31 + i);
        }
        Snapshot.Key content = new Snapshot.Key(new Snapshot.Key(), hash);
        Snapshot.Header header = new Snapshot.Header(content, UUID.randomUUID(), "maker", new Date(), name);
        return new Snapshot.Key(content, header);
    }

    /** What ContainerElectronicLibrary.sendSelectedToServer sends, as the server reads it. */
    private static void select(ServerPlayer player, Snapshot.Key key) throws Exception {
        ContainerElectronicLibrary menu = (ContainerElectronicLibrary) player.containerMenu;
        int id = ContainerElectronicLibrary.ID_SELECTED;
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBoolean(key != null);
        if (key != null) {
            key.writeToByteBuf(buffer);
        }
        menu.readMessage(id, buffer, BCNetworkSide.SERVER, serverContext(player));
    }

    private static ServerPlayer openLibrary(GameTestHelper helper, TileElectronicLibrary tile, GameProfile profile,
        BlockPos pos) {
        ServerPlayer player = FakePlayerProvider.INSTANCE.getFakePlayer(helper.getLevel(), profile, pos);
        player.containerMenu = tile.createMenu(1, player.getInventory(), player);
        return player;
    }

    /** An upload part from {@code player} for {@code key}; true if the server accepted it. */
    private static boolean uploadAccepted(TileElectronicLibrary tile, ServerPlayer player, Snapshot.Key key) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        key.writeToByteBuf(buffer);
        buffer.writeBoolean(false);
        buffer.writeByteArray(new byte[] { 1, 2, 3 });
        try {
            tile.readPayload(TileElectronicLibrary.NET_UP, buffer, BCNetworkSide.SERVER, serverContext(player));
            return true;
        } catch (Exception refused) {
            return false;
        }
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void librarySelectionIsPerPlayer(GameTestHelper helper) {
        BlockPos libraryRel = new BlockPos(1, 1, 1);
        try {
            run(helper, libraryRel);
        } catch (Failure failure) {
            helper.fail(failure.getMessage());
            return;
        } catch (Exception e) {
            helper.fail("Library selection test threw " + e);
            return;
        } finally {
            helper.setBlock(libraryRel, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    private static void run(GameTestHelper helper, BlockPos libraryRel) throws Exception {
        helper.setBlock(libraryRel, BCBuildersBlocks.LIBRARY.get().defaultBlockState());
        BlockEntity entity = GameTestCompat.getBlockEntity(helper, libraryRel);
        require(entity instanceof TileElectronicLibrary, "no TileElectronicLibrary");
        TileElectronicLibrary library = (TileElectronicLibrary) entity;
        BlockPos pos = helper.absolutePos(libraryRel);
        ServerPlayer playerA = openLibrary(helper, library, PLAYER_A, pos);
        ServerPlayer playerB = openLibrary(helper, library, PLAYER_B, pos);
        require(playerA.containerMenu.stillValid(playerA) && playerB.containerMenu.stillValid(playerB),
            "the fake players cannot use the library");

        Snapshot.Key keyA = key("player-a-blueprint", 1);
        Snapshot.Key keyB = key("player-b-blueprint", 2);

        // A selects: the upload works on A's blueprint and asks A's client only.
        select(playerA, keyA);
        require(keyA.equals(library.selected), "A's selection did not reach the library");
        require(library.isUploadRequester(playerA), "A selected but is not asked for the blueprint");
        require(!library.isUploadRequester(playerB), "B is asked for A's blueprint");

        // B selects something else: the latest choice drives the upload, and only B may answer now.
        select(playerB, keyB);
        require(keyB.equals(library.selected), "B's selection did not reach the library");
        require(library.isUploadRequester(playerB) && !library.isUploadRequester(playerA),
            "the upload request does not follow the player who selected last");
        require(library.isSelectedBy(GameTestCompat.profileId(PLAYER_B)) && !library.isSelectedBy(GameTestCompat.profileId(PLAYER_A)),
            "the library does not record who selected");

        // Clearing (A deletes their entry) must not clear B's selection.
        select(playerA, null);
        require(keyB.equals(library.selected) && library.isSelectedBy(GameTestCompat.profileId(PLAYER_B)),
            "A clearing their selection cleared B's");

        // Uploads: B (the selector) is accepted, A (another viewer answering the old broadcast) is refused.
        library.invUpIn.setStackInSlot(0,
            buildcraft.builders.item.ItemSnapshot.getClean(buildcraft.lib.internal.enums.EnumSnapshotType.BLUEPRINT));
        require(!uploadAccepted(library, playerA, keyB), "a player who did not select the blueprint may upload it");
        require(uploadAccepted(library, playerB, keyB), "the selecting player's upload part was refused");

        // B clears their own selection: nothing is selected any more.
        select(playerB, null);
        require(library.selected == null && !library.isUploadRequester(playerB), "B could not clear their selection");

        // A player who closed the library is not asked.
        select(playerA, keyA);
        playerA.containerMenu = playerA.inventoryMenu;
        require(!library.isUploadRequester(playerA), "a player without the library open is asked for the blueprint");
    }
}
