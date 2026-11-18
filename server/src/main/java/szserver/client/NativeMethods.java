package szserver.client;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Return types are part of JVM descriptors, including obfuscated return-only overloads. */
final class NativeMethods {
    private NativeMethods() {}

    static Method instance(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
            throws NoSuchMethodException {
        return Arrays.stream(owner.getMethods())
                .filter(method -> method.getName().equals(name)
                        && method.getReturnType() == result
                        && !Modifier.isStatic(method.getModifiers())
                        && Arrays.equals(method.getParameterTypes(), parameters))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(owner.getName() + "." + name
                        + Arrays.toString(parameters) + ":" + result.getName()));
    }
}
