package buildcraft.builders.gui;

import java.lang.reflect.Field;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * ESC must close the Architect Table screen even while its blueprint name field has focus (it does by default).
 * The screen used to compare the key with GLFW's escape code 256; 26.x input uses SDL scancodes (escape is 41), so
 * the check never matched and the focused field swallowed the key: the screen could not be closed with ESC.
 *
 * <p>No Minecraft client runs in unit tests, so the screen and its field are allocated without constructors and only
 * the state the key path reads is set. {@code onClose()} is overridden to record the close.
 */
class GuiArchitectTableEscapeTester {
    private static final int SDLK_ESCAPE = 27;
    private static final int SDLK_A = 'a';

    static class RecordingGui extends GuiArchitectTable {
        int closed;

        RecordingGui() {
            super(null, null, null);
        }

        @Override
        public void onClose() {
            closed++;
        }
    }

    @Test
    void escapeClosesTheScreenWhileTheNameFieldHasFocus() throws Exception {
        RecordingGui gui = newGui();

        Assertions.assertTrue(gui.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, SDLK_ESCAPE, 0)));
        Assertions.assertEquals(1, gui.closed, "ESC did not close the Architect Table screen");
    }

    @Test
    void otherKeysStillGoToTheFocusedNameField() throws Exception {
        RecordingGui gui = newGui();

        // The inventory key (E by default) and any other key must type into the field, not close the screen.
        Assertions.assertTrue(gui.keyPressed(new KeyEvent(InputConstants.KEY_A, SDLK_A, 0)));
        Assertions.assertTrue(gui.keyPressed(new KeyEvent(InputConstants.KEY_E, 'e', 0)));
        Assertions.assertEquals(0, gui.closed);
    }

    private static RecordingGui newGui() throws Exception {
        Unsafe unsafe = unsafe();
        RecordingGui gui = (RecordingGui) unsafe.allocateInstance(RecordingGui.class);
        EditBox field = (EditBox) unsafe.allocateInstance(EditBox.class);
        set(AbstractWidget.class, field, "active", true);
        set(AbstractWidget.class, field, "visible", true);
        set(AbstractWidget.class, field, "focused", true);
        set(EditBox.class, field, "isEditable", true);
        set(EditBox.class, field, "value", "");
        Assertions.assertTrue(field.canConsumeInput(), "test setup: the name field should have focus");
        set(GuiArchitectTable.class, gui, "nameField", field);
        return gui;
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }
}
