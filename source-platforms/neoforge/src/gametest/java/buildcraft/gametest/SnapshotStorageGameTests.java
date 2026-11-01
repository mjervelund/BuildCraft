/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.gametest;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.builders.snapshot.GlobalSavedDataSnapshots;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.Template;
import buildcraft.lib.BCLib;
import buildcraft.lib.net.BCNetworkSide;

/** Blueprint files written by the Architect Table and the Electronic Library. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SnapshotStorageGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";
    private static final UUID OWNER = UUID.fromString("6f4d4b62-3f7e-4d0b-9a59-2a8f3b1c0d11");

    private SnapshotStorageGameTests() {
    }

    /** A random 16x1x1 template with a header, so each run writes a file that did not exist before. */
    private static Template newTemplate(String name) {
        Template template = new Template();
        template.size = new BlockPos(16, 1, 1);
        template.offset = BlockPos.ZERO;
        template.facing = Direction.NORTH;
        template.data = new BitSet(16);
        long bits = UUID.randomUUID().getMostSignificantBits();
        for (int i = 0; i < 16; i++) {
            template.data.set(i, ((bits >>> i) & 1) != 0);
        }
        template.computeKey();
        Snapshot.Header header = new Snapshot.Header(template.key, OWNER, "BCTestOwner", new Date(), name);
        template.key = new Snapshot.Key(template.key, header);
        return template;
    }

    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder(failure.toString());
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            text.append(" <- ").append(cause);
        }
        return text.toString();
    }

    private static long leftoverTempFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".tmp")).count();
        }
    }

    /**
     * The Architect Table path: {@code cacheServerSnapshot} -> {@code writeSnapshotFile}. The file must be written,
     * fsynced and renamed into place; before the fix the fsync ran on a descriptor NbtIo had already closed.
     */
    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void serverSnapshotIsWrittenAndSynced(GameTestHelper helper) {
        Template template = newTemplate("server-sync-test");
        Path directory = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
            .resolve("buildcraft/blueprints/server");
        Path file = directory.resolve(template.key + ".bcnbt");
        try {
            GlobalSavedDataSnapshots.cacheServerSnapshot(helper.getLevel(), template);
        } catch (RuntimeException failure) {
            helper.fail("Writing a server blueprint failed: " + describe(failure));
            return;
        }
        if (!Files.isRegularFile(file)) {
            helper.fail("Server blueprint file was not created: " + file);
            return;
        }
        Snapshot read = GlobalSavedDataSnapshots.getSnapshotForConstruction(helper.getLevel(), template.key);
        if (!(read instanceof Template readTemplate) || !template.data.equals(readTemplate.data)) {
            helper.fail("Server blueprint did not read back with the same content: " + read);
            return;
        }
        try {
            if (leftoverTempFiles(directory) != 0) {
                helper.fail("Temporary blueprint files were left in " + directory);
                return;
            }
        } catch (IOException e) {
            helper.fail("Could not list " + directory + ": " + e);
            return;
        }
        helper.succeed();
    }

    /** The Electronic Library download path ({@code saveClientSnapshot}) writes through the same method. */
    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void clientLibrarySnapshotIsWrittenAndSynced(GameTestHelper helper) {
        Template template = newTemplate("client-sync-test");
        File directory = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
            .resolve("buildcraft-test-client-library-" + UUID.randomUUID()).toFile();
        try {
            Constructor<GlobalSavedDataSnapshots> constructor = GlobalSavedDataSnapshots.class
                .getDeclaredConstructor(BCNetworkSide.class, File.class);
            constructor.setAccessible(true);
            GlobalSavedDataSnapshots store = constructor.newInstance(BCNetworkSide.CLIENT, directory);
            Method write = GlobalSavedDataSnapshots.class.getDeclaredMethod("writeClientSnapshot", Snapshot.class);
            write.setAccessible(true);
            write.invoke(store, template);
        } catch (InvocationTargetException failure) {
            helper.fail("Writing a client blueprint failed: " + describe(failure.getCause()));
            return;
        } catch (ReflectiveOperationException e) {
            helper.fail("Test setup failed: " + e);
            return;
        }
        File[] written = directory.listFiles((dir, name) -> name.endsWith(".bcnbt"));
        if (written == null || written.length != 1) {
            helper.fail("Expected one client blueprint file in " + directory + ", found "
                + (written == null ? "none" : written.length));
            return;
        }
        try {
            if (leftoverTempFiles(directory.toPath()) != 0) {
                helper.fail("Temporary blueprint files were left in " + directory);
                return;
            }
        } catch (IOException e) {
            helper.fail("Could not list " + directory + ": " + e);
            return;
        }
        helper.succeed();
    }
}
