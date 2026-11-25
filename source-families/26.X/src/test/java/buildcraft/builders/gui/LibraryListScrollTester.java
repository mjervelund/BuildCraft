package buildcraft.builders.gui;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** The Electronic Library list used to show only its first 13 blueprints; it now scrolls. */
class LibraryListScrollTester {
    private static final int VISIBLE = 13;

    @Test
    void everyEntryIsReachableByScrolling() {
        int size = 40;
        int offset = 0;
        for (int i = 0; i < 100; i++) {
            offset = LibraryListScroll.scroll(offset, -1, size, VISIBLE);
        }
        Assertions.assertEquals(size - VISIBLE, offset, "wheel down stops with the last entry on the last row");
        Assertions.assertEquals(size - 1, offset + VISIBLE - 1, "the last blueprint is visible");
        for (int i = 0; i < 100; i++) {
            offset = LibraryListScroll.scroll(offset, 1, size, VISIBLE);
        }
        Assertions.assertEquals(0, offset, "wheel up returns to the first entry");
    }

    @Test
    void shortListsDoNotScroll() {
        Assertions.assertEquals(0, LibraryListScroll.scroll(0, -1, 5, VISIBLE));
        Assertions.assertEquals(0, LibraryListScroll.scroll(0, -1, VISIBLE, VISIBLE));
        Assertions.assertNull(LibraryListScroll.thumb(0, VISIBLE, VISIBLE, 108));
    }

    @Test
    void offsetIsClampedWhenEntriesAreDeleted() {
        Assertions.assertEquals(7, LibraryListScroll.clamp(27, 20, VISIBLE));
        Assertions.assertEquals(0, LibraryListScroll.clamp(27, 3, VISIBLE));
        Assertions.assertEquals(0, LibraryListScroll.clamp(-4, 30, VISIBLE));
    }

    @Test
    void thumbSpansTheTrack() {
        int[] top = LibraryListScroll.thumb(0, 26, VISIBLE, 108);
        int[] bottom = LibraryListScroll.thumb(13, 26, VISIBLE, 108);
        Assertions.assertArrayEquals(new int[] { 0, 54 }, top);
        Assertions.assertEquals(108, bottom[0] + bottom[1], "thumb reaches the bottom at the last offset");
    }
}
