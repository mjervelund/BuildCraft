/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.builders.gui;

/** Scroll position of the Electronic Library's blueprint list (pure logic, unit tested). */
public final class LibraryListScroll {
    private LibraryListScroll() {
    }

    /** The largest first-row index that still fills the list, 0 when everything fits. */
    public static int maxOffset(int size, int visible) {
        return Math.max(0, size - Math.max(0, visible));
    }

    public static int clamp(int offset, int size, int visible) {
        return Math.max(0, Math.min(offset, maxOffset(size, visible)));
    }

    /** One row per wheel notch: wheel up (positive delta) shows earlier entries. */
    public static int scroll(int offset, double wheelDelta, int size, int visible) {
        if (wheelDelta == 0) {
            return clamp(offset, size, visible);
        }
        return clamp(offset + (wheelDelta > 0 ? -1 : 1), size, visible);
    }

    /** Scroll bar thumb as {top, height} in pixels inside a track of {@code trackHeight}, or null if all rows fit. */
    public static int[] thumb(int offset, int size, int visible, int trackHeight) {
        if (size <= visible || visible <= 0 || trackHeight <= 0) {
            return null;
        }
        int height = Math.max(4, trackHeight * visible / size);
        int top = (trackHeight - height) * clamp(offset, size, visible) / maxOffset(size, visible);
        return new int[] { top, height };
    }
}
