package rtfstreamsconnector.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;

// Access helper for private fields
public final class PrivateFieldReflector {
    private PrivateFieldReflector() {}

    public static VarHandle varHandle(Class<?> owner, String fieldName, Class<?> fieldType) {
        try{
            return MethodHandles.privateLookupIn(owner, MethodHandles.lookup())
                .findVarHandle(owner, fieldName, fieldType);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Unable to access private field " + owner.getSimpleName()
                + "." + fieldName + ":" + fieldType.getSimpleName(), e);
        }
    }

    // Invokes a public accessor of a package-private record/class
    public static String accessor(Object target, String accessor) {
        try {
            Method method = target.getClass().getMethod(accessor);
            method.setAccessible(true); // public method on a non-public class still needs this
            return String.valueOf(method.invoke(target));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Unable to invoke " + target.getClass().getSimpleName()
                + "." + accessor + "()", e);
        }
    }
}
