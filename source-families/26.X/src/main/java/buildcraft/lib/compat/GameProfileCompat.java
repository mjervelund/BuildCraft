package buildcraft.lib.compat;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.util.UUID;

/** Compatibility accessors for Mojang's record-style GameProfile fields. */
public final class GameProfileCompat {
    // GameProfile is final, so the accessors are resolved once instead of on every call (owner and permission checks
    // run per machine action).
    private static final Method ID = find("id", "getId");
    private static final Method NAME = find("name", "getName");

    private GameProfileCompat() {
    }

    public static UUID id(GameProfile profile) {
        if (profile == null) {
            return null;
        }
        Object value = call(ID, profile);
        return value instanceof UUID uuid ? uuid : null;
    }

    public static String name(GameProfile profile) {
        if (profile == null) {
            return null;
        }
        Object value = call(NAME, profile);
        return value instanceof String name ? name : null;
    }

    private static Method find(String first, String fallback) {
        for (String methodName : new String[] { first, fallback }) {
            try {
                return GameProfile.class.getMethod(methodName);
            } catch (NoSuchMethodException ignored) {
                // Try the next spelling.
            }
        }
        return null;
    }

    private static Object call(Method method, GameProfile profile) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(profile);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
