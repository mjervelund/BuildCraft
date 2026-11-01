/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.gametest;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import io.netty.buffer.Unpooled;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.GlobalSavedDataSnapshots;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.tile.TileArchitectTable;
import buildcraft.builders.tile.TileElectronicLibrary;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.data.NbtSquishConstants;
import buildcraft.lib.internal.enums.EnumSnapshotType;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.lib.nbt.NbtSquisher;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

/**
 * The Electronic Library round trip in one JVM: Architect Table scan, download into the (client) blueprint
 * library, list, upload of a client-only blueprint by one of two players with the library open, delete.
 * Network payloads are built exactly as TileElectronicLibrary writes them and fed to readPayload, because the
 * GameTest server has no client connection.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class ElectronicLibraryGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";
    private static final int PART_BYTES = 4 * 1024;
    private static final GameProfile PLAYER_A =
        new GameProfile(UUID.fromString("5d0c8a3e-1b2f-4c6d-8e9f-0a1b2c3d4e5f"), "BCLibraryA");
    private static final GameProfile PLAYER_B =
        new GameProfile(UUID.fromString("6e1d9b4f-2c3a-4d7e-9f0a-1b2c3d4e5f60"), "BCLibraryB");

    private ElectronicLibraryGameTests() {
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

    private static byte[] encode(Snapshot snapshot) {
        return NbtSquisher.squish(Snapshot.writeToNBT(snapshot), NbtSquishConstants.BUILDCRAFT_V1_COMPRESSED);
    }

    /** What the client receives for a download (TileElectronicLibrary.sendSnapshotDownload). */
    private static void receiveDownload(TileElectronicLibrary tile, Snapshot snapshot) throws Exception {
        byte[] encoded = encode(snapshot);
        int parts = Math.max(1, (encoded.length + PART_BYTES - 1) / PART_BYTES);
        for (int index = 0; index < parts; index++) {
            int offset = index * PART_BYTES;
            int amount = Math.min(PART_BYTES, encoded.length - offset);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            buffer.writeBoolean(true);
            buffer.writeInt(1);
            buffer.writeVarInt(index);
            buffer.writeVarInt(parts);
            buffer.writeVarInt(amount);
            buffer.writeBytes(encoded, offset, amount);
            tile.readPayload(TileElectronicLibrary.NET_DOWN, buffer, BCNetworkSide.CLIENT, null);
        }
    }

    /** What a client sends for an upload (TileElectronicLibrary.readPayload NET_UP, client side). */
    private static void sendUpload(TileElectronicLibrary tile, ServerPlayer player, Snapshot.Key selected,
        Snapshot snapshot) throws Exception {
        byte[] encoded = encode(snapshot);
        int offset = 0;
        while (true) {
            int amount = Math.min(PART_BYTES, encoded.length - offset);
            boolean last = offset + amount >= encoded.length && amount < PART_BYTES;
            byte[] part = new byte[amount];
            System.arraycopy(encoded, offset, part, 0, amount);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            selected.writeToByteBuf(buffer);
            buffer.writeBoolean(last);
            buffer.writeByteArray(part);
            tile.readPayload(TileElectronicLibrary.NET_UP, buffer, BCNetworkSide.SERVER, serverContext(player));
            offset += amount;
            if (last) {
                return;
            }
        }
    }

    private static Snapshot.Key findListed(Snapshot.Key key) {
        List<Snapshot.Key> list = GlobalSavedDataSnapshots.getClientSnapshotList();
        for (Snapshot.Key listed : list) {
            if (java.util.Arrays.equals(listed.hash, key.hash)) {
                return listed;
            }
        }
        return null;
    }

    private static ServerPlayer openLibrary(GameTestHelper helper, TileElectronicLibrary tile, GameProfile profile,
        BlockPos pos) {
        ServerPlayer player = FakePlayerProvider.INSTANCE.getFakePlayer(helper.getLevel(), profile, pos);
        player.containerMenu = tile.createMenu(1, player.getInventory(), player);
        return player;
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 40)
    public static void libraryRoundTrip(GameTestHelper helper) {
        BlockPos architectRel = new BlockPos(2, 1, 2);
        BlockPos libraryRel = new BlockPos(2, 1, 0);
        try {
            run(helper, architectRel, libraryRel);
        } catch (Failure failure) {
            helper.fail(failure.getMessage());
            return;
        } catch (Exception e) {
            helper.fail("Library round trip threw " + e);
            return;
        } finally {
            helper.setBlock(architectRel, Blocks.AIR.defaultBlockState());
            helper.setBlock(libraryRel, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    private static void run(GameTestHelper helper, BlockPos architectRel, BlockPos libraryRel) throws Exception {
        String name = "library-e2e-" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Scan a 2x2x2 box with a few blocks into a blueprint.
        helper.setBlock(new BlockPos(0, 1, 0), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(1, 1, 0), Blocks.OAK_PLANKS.defaultBlockState());
        helper.setBlock(new BlockPos(0, 2, 1), Blocks.GLASS.defaultBlockState());
        helper.setBlock(architectRel, BCBuildersBlocks.ARCHITECT.get().defaultBlockState());
        BlockEntity architectEntity = GameTestCompat.getBlockEntity(helper, architectRel);
        require(architectEntity instanceof TileArchitectTable, "no TileArchitectTable");
        TileArchitectTable architect = (TileArchitectTable) architectEntity;
        BlockPos min = helper.absolutePos(new BlockPos(0, 1, 0));
        architect.box.reset();
        architect.box.setMin(min);
        architect.box.setMax(min.offset(1, 1, 1));
        architect.name = name;
        Field isValid = TileArchitectTable.class.getDeclaredField("isValid");
        isValid.setAccessible(true);
        isValid.setBoolean(architect, true);
        architect.invSnapshotIn.setStackInSlot(0, ItemSnapshot.getClean(EnumSnapshotType.BLUEPRINT));
        architect.update();
        ItemStack scanned = architect.invSnapshotOut.getStackInSlot(0);
        Snapshot.Header scannedHeader = ItemSnapshot.getHeader(scanned);
        require(scannedHeader != null, "the Architect Table did not produce a blueprint (save failed?)");
        require(name.equals(scannedHeader.name), "scanned blueprint has the wrong name: " + scannedHeader.name);
        Snapshot stored = GlobalSavedDataSnapshots.getSnapshotForConstruction(helper.getLevel(), scannedHeader.key);
        require(stored instanceof Blueprint, "the scanned blueprint is not in the world's blueprint store");

        // 2. Download it into the client library.
        helper.setBlock(libraryRel, BCBuildersBlocks.LIBRARY.get().defaultBlockState());
        BlockEntity libraryEntity = GameTestCompat.getBlockEntity(helper, libraryRel);
        require(libraryEntity instanceof TileElectronicLibrary, "no TileElectronicLibrary");
        TileElectronicLibrary library = (TileElectronicLibrary) libraryEntity;
        BlockPos libraryPos = helper.absolutePos(libraryRel);
        ServerPlayer playerA = openLibrary(helper, library, PLAYER_A, libraryPos);
        ServerPlayer playerB = openLibrary(helper, library, PLAYER_B, libraryPos);
        require(playerA.containerMenu.stillValid(playerA), "player A cannot use the library");

        library.invDownIn.setStackInSlot(0, scanned.copy());
        for (int i = 0; i <= TileElectronicLibrary.TRANSFER_TIME + 1; i++) {
            library.update();
        }
        require(!library.invDownOut.getStackInSlot(0).isEmpty() && library.invDownIn.getStackInSlot(0).isEmpty(),
            "the download did not finish");
        Snapshot download = stored.copy();
        download.key = new Snapshot.Key(download.key, scannedHeader);
        receiveDownload(library, download);

        // 3. The client library lists it by name.
        Snapshot.Key listed = findListed(scannedHeader.key);
        require(listed != null, "the downloaded blueprint is not in the client library");
        require(listed.header != null && name.equals(listed.header.name),
            "the client library lists the blueprint without its name: " + listed.header);

        // 4. Upload a blueprint the server has never seen (as if made in another world).
        Blueprint clientOnly = (Blueprint) GlobalSavedDataSnapshots.getClientSnapshot(listed).copy();
        clientOnly.offset = clientOnly.offset.offset(0, 1, 0);
        clientOnly.computeKey();
        String uploadName = name + "-upload";
        Snapshot.Header uploadHeader = new Snapshot.Header(clientOnly.key, GameTestCompat.profileId(PLAYER_A), GameTestCompat.profileName(PLAYER_A),
            new Date(), uploadName);
        clientOnly.key = new Snapshot.Key(clientOnly.key, uploadHeader);
        require(GlobalSavedDataSnapshots.getSnapshotForConstruction(helper.getLevel(), clientOnly.key) == null,
            "the upload test blueprint is already on the server");
        GlobalSavedDataSnapshots.saveClientSnapshot(clientOnly);
        Snapshot.Key uploadKey = findListed(clientOnly.key);
        require(uploadKey != null && uploadKey.header != null, "the client-only blueprint is not listed with a header");

        library.selected = uploadKey; // ContainerElectronicLibrary ID_SELECTED
        library.invUpIn.setStackInSlot(0, ItemSnapshot.getClean(EnumSnapshotType.BLUEPRINT));
        for (int i = 0; i <= TileElectronicLibrary.TRANSFER_TIME + 1; i++) {
            library.update();
        }
        require(library.invUpOut.getStackInSlot(0).isEmpty(), "the upload finished without the client's data");
        Snapshot clientCopy = GlobalSavedDataSnapshots.getClientSnapshot(uploadKey);
        require(clientCopy != null, "the selected client blueprint cannot be read back");
        sendUpload(library, playerA, uploadKey, clientCopy);
        Snapshot.Header uploaded = ItemSnapshot.getHeader(library.invUpOut.getStackInSlot(0));
        require(uploaded != null && uploadName.equals(uploaded.name), "the upload did not produce the named blueprint");
        require(library.invUpIn.getStackInSlot(0).isEmpty(), "the clean blueprint was not consumed");
        require(GlobalSavedDataSnapshots.getSnapshotForConstruction(helper.getLevel(), uploaded.key) != null,
            "the uploaded blueprint is not in the world's blueprint store");

        // 5. A second player whose client answered the same upload request is refused without side effects.
        ItemStack before = library.invUpOut.getStackInSlot(0).copy();
        try {
            sendUpload(library, playerB, uploadKey, clientCopy);
        } catch (RuntimeException expected) {
            // MessageManager drops invalid client payloads.
        }
        require(ItemStack.matches(before, library.invUpOut.getStackInSlot(0)),
            "a second player's upload changed the finished blueprint");

        // 6. Delete from the client library.
        GlobalSavedDataSnapshots.removeClientSnapshot(uploadKey);
        GlobalSavedDataSnapshots.removeClientSnapshot(listed);
        require(findListed(clientOnly.key) == null && findListed(scannedHeader.key) == null,
            "deleted blueprints are still listed");
    }
}
