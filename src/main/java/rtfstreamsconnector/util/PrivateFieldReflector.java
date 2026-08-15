package rtfstreamsconnector.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;

// Access helper for private fields
public final class PrivateFieldReflector {
    private PrivateFieldReflector() {}

    public static VarHandle varHandle(Class<?> owner, String fieldName, Class<?> fieldType) {
        try {
            return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).findVarHandle(owner, fieldName, fieldType);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Unable to access private field " + owner.getSimpleName() + "." + fieldName + ":" + fieldType.getSimpleName(), e);
        }
    }

    // Method handle bound to a private instance method (same lookup as varHandle)
    public static MethodHandle methodHandle(Class<?> owner, String methodName, MethodType type) {
        try {
            return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).findVirtual(owner, methodName, type);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Unable to access private method " + owner.getSimpleName() + "." + methodName + "()", e);
        }
    }

    // Invokes a method handle with automatic boxing/unboxing and wraps the result as T
    @SuppressWarnings("unchecked")
    public static <T> T invoke(MethodHandle handle, Object target, Object... args) {
        try {
            Object[] allArgs = new Object[args.length + 1];
            allArgs[0] = target;
            System.arraycopy(args, 0, allArgs, 1, args.length);
            return (T) handle.invokeWithArguments(allArgs);
        } catch (Throwable e) {
            throw new RuntimeException("Unable to invoke method " + handle, e);
        }
    }

    // Invokes a public accessor of a package-private record/class
    public static String accessor(Object target, String accessor) {
        try {
            Method method = target.getClass().getMethod(accessor);
            method.setAccessible(true); // public method on a non-public class still needs this
            return String.valueOf(method.invoke(target));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Unable to invoke " + target.getClass().getSimpleName() + "." + accessor + "()", e);
        }
    }
}
