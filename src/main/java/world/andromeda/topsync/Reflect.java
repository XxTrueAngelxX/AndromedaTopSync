package world.andromeda.topsync;

import java.lang.reflect.*;
import java.util.*;

/** Version-checked adapter for APIs loaded by the owning plugins, without shading them. */
final class Reflect {
    private Reflect() { }
    static Object call(Object target, String name, Object... args) {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] types = method.getParameterTypes(); boolean matches = true;
            for (int i=0;i<args.length;i++) {
                Class<?> t = types[i].isPrimitive() ? wrapper(types[i]) : types[i];
                if (args[i] != null && !t.isInstance(args[i])) { matches=false; break; }
            }
            if (!matches) continue;
            try { method.trySetAccessible(); return method.invoke(target instanceof Class<?> ? null : target,args); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(type.getName()+"."+name,e); }
        }
        throw new IllegalStateException("Unsupported API: "+type.getName()+"."+name);
    }
    static Object field(Object target,String name) {
        try { Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported field "+name,e); }
    }
    static Class<?> load(ClassLoader loader,String name) {
        try { return Class.forName(name,true,loader); }
        catch (ClassNotFoundException e) { throw new IllegalStateException(name,e); }
    }
    private static Class<?> wrapper(Class<?> c) {
        if(c==int.class)return Integer.class;if(c==long.class)return Long.class;
        if(c==double.class)return Double.class;if(c==boolean.class)return Boolean.class;
        return c;
    }
}
