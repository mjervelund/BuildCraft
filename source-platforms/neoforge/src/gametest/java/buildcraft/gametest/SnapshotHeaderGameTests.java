/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.gametest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.Date;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import buildcraft.builders.snapshot.GlobalSavedDataSnapshots;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.Template;
import buildcraft.lib.BCLib;

/** Blueprint names, owners and settings (the header) must survive validation, saving and loading. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SnapshotHeaderGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";

    private SnapshotHeaderGameTests() {
    }

    private static Snapshot validatedCopy(Snapshot snapshot) throws ReflectiveOperationException {
        Method method = GlobalSavedDataSnapshots.class.getDeclaredMethod("validatedCopy", Snapshot.class);
        method.setAccessible(true);
        return (Snapshot) method.invoke(null, snapshot);
    }

    private static Template namedTemplate(String name) {
        Template template = new Template();
        template.size = new BlockPos(4, 1, 1);
        template.offset = BlockPos.ZERO;
        template.facing = Direction.NORTH;
        template.data = new BitSet(4);
        template.data.set(1);
        template.data.set(2);
        template.computeKey();
        Snapshot.Header header = new Snapshot.Header(template.key,
            UUID.fromString("0b7e1c55-6a1f-4b8e-9d5c-3f2a1e0d9c8b"), "BCTestOwner", new Date(), name);
        template.key = new Snapshot.Key(template.key, header);
        return template;
    }

    /**
     * Every snapshot the library saves or reads goes through validatedCopy. It used to take the header from the
     * copy, whose key never has one, so client library files were named after the type ("template-<hash>"), the
     * library listed hashes, and a selected entry had no header, so uploads never started.
     */
    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void validationKeepsTheBlueprintHeader(GameTestHelper helper) {
        Template template = namedTemplate("keep-header-test");
        try {
            Snapshot checked = validatedCopy(template);
            if (checked == null || checked.key.header == null
                || !"keep-header-test".equals(checked.key.header.name)) {
                helper.fail("Validation dropped the header: " + (checked == null ? null : checked.key.header));
                return;
            }
            // As read back from a .bcnbt file.
            Snapshot reloaded = validatedCopy(Snapshot.readFromNBT(Snapshot.writeToNBT(template)));
            if (reloaded == null || reloaded.key.header == null
                || !template.key.header.equals(reloaded.key.header)) {
                helper.fail("A saved and reloaded blueprint lost its header: "
                    + (reloaded == null ? null : reloaded.key.header));
                return;
            }

            // A header that belongs to other content is not attached to this one.
            Template other = namedTemplate("other");
            other.data.set(0);
            other.computeKey();
            Snapshot mismatched = validatedCopy(other);
            if (mismatched != null && mismatched.key.header != null) {
                helper.fail("A header describing different content was kept");
                return;
            }
        } catch (InvocationTargetException e) {
            helper.fail("validatedCopy threw " + e.getCause());
            return;
        } catch (ReflectiveOperationException | RuntimeException e) {
            helper.fail("Test failed: " + e);
            return;
        } catch (Exception e) {
            helper.fail("Reading the blueprint NBT failed: " + e);
            return;
        }
        helper.succeed();
    }
}
