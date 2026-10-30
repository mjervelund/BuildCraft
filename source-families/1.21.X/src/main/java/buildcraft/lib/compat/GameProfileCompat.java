//? source if >=1.21.11
package buildcraft.lib.compat;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.util.UUID;

/** Compatibility accessors for Mojang's record-style GameProfile fields in 1.21.11. */
public final class GameProfileCompat {
    private GameProfileCompat() {
    }

    public static UUID id(GameProfile profile) {
        if (profile == null) {
            return null;
        }
        Object value = call(profile, ID);
        return value instanceof UUID uuid ? uuid : null;
    }

    public static String name(GameProfile profile) {
        if (profile == null) {
            return null;
        }
        Object value = call(profile, NAME);
        return value instanceof String name ? name : null;
    }

    // Owner ids are read on permission checks and in machine ticks. Resolving the accessor with Class.getMethod on
    // every call was the costly part, so resolve it once per GameProfile class.
    private static final ClassValue<Method> ID = accessor("id", "getId");
    private static final ClassValue<Method> NAME = accessor("name", "getName");

    private static ClassValue<Method> accessor(String first, String fallback) {
        return new ClassValue<>() {
            @Override
            protected Method computeValue(Class<?> type) {
                for (String methodName : new String[] { first, fallback }) {
                    try {
                        return type.getMethod(methodName);
                    } catch (NoSuchMethodException ignored) {
                        // Try the next spelling.
                    }
                }
                return null;
            }
        };
    }

    private static Object call(GameProfile profile, ClassValue<Method> accessor) {
        Method method = accessor.get(profile.getClass());
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
