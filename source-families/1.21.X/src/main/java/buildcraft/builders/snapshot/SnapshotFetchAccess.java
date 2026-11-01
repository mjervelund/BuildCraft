/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import java.util.Arrays;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import buildcraft.builders.item.ItemSnapshot;

/**
 * Who may download a stored blueprint/template from the server ({@link MessageSnapshotRequest}).
 *
 * <p>The client asks for snapshot content to show a blueprint item's tooltip and the Replacer screen. Those clients
 * have the item in front of them, so a player may fetch a snapshot when:
 * <ul>
 * <li>they have operator permission (level 2), or</li>
 * <li>they are its owner (the uploader/scanner recorded in the stored header), or</li>
 * <li>they hold a written blueprint/template item for exactly that content: in their inventory, on the cursor, or in a
 * slot of the menu they have open (a Builder, Replacer, chest, ...).</li>
 * </ul>
 * Knowing a content hash alone is no longer enough.
 */
public final class SnapshotFetchAccess {
    private SnapshotFetchAccess() {
    }

    public static boolean canFetch(ServerPlayer player, Snapshot stored) {
        if (player == null || stored == null || stored.key == null) {
            return false;
        }
        if (player.hasPermissions(2)) {
            return true;
        }
        Snapshot.Header header = stored.key.header;
        if (header != null && player.getUUID().equals(header.owner)) {
            return true;
        }
        return holdsSnapshotItem(player, stored.key.hash);
    }

    static boolean holdsSnapshotItem(ServerPlayer player, byte[] hash) {
        if (hash == null) {
            return false;
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (isItemFor(inventory.getItem(i), hash)) {
                return true;
            }
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu != null) {
            if (isItemFor(menu.getCarried(), hash)) {
                return true;
            }
            for (Slot slot : menu.slots) {
                if (isItemFor(slot.getItem(), hash)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isItemFor(ItemStack stack, byte[] hash) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof ItemSnapshot)) {
            return false;
        }
        Snapshot.Header header = ItemSnapshot.getHeader(stack);
        return header != null && header.key != null && Arrays.equals(header.key.hash, hash);
    }
}
