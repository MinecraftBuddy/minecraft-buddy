package adris.altoclef.util.helpers;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

// the statics that can hold state from one order to the next, read from the compiled classes (RunMemoryGuardTest): a
// non-final static written outside its class's initializer, a final array stored into outside it, a final collection, map,
// atomic or object of our own with fields of its own changed outside it. a constant (List.of, an array only read, a
// Pattern) is no state. read from the bytecode by method: a method that reads the field and changes one of its kind counts,
// which can take a constant for state now and then, never state for a constant
final class StateStatics {
    private static final Set<String> MUTATORS = Set.of("add", "addAll", "addFirst", "addLast", "put", "putAll", "putIfAbsent",
            "remove", "removeAll", "removeIf", "retainAll", "clear", "set", "compute", "computeIfAbsent", "computeIfPresent",
            "merge", "offer", "offerFirst", "offerLast", "poll", "pollFirst", "pollLast", "push", "pop", "replaceAll",
            "incrementAndGet", "getAndIncrement", "decrementAndGet", "getAndDecrement", "addAndGet", "getAndAdd", "getAndSet",
            "compareAndSet", "lazySet", "accumulateAndGet", "updateAndGet", "getAndUpdate", "trim", "sort");

    private record Static(String owner, String name, String desc, boolean isFinal) {
    }

    private StateStatics() {
    }

    // every class under the package folder of this root (a compiled classes folder), as "a.b.C#field"
    static Set<String> scan(Path root, String pkg, ClassLoader loader) throws IOException {
        Map<String, Static> statics = new HashMap<>();
        Set<String> written = new HashSet<>();
        // per method: the static fields it reads, the owners of the mutators it calls, and its array stores
        Map<String, Set<String>> mutatedOwners = new HashMap<>();
        Set<String> arrayStored = new HashSet<>();
        List<Path> files;
        try (var s = Files.walk(root.resolve(pkg.replace('.', '/')))) {
            files = s.filter(p -> p.toString().endsWith(".class")).toList();
        }
        for (Path p : files) {
            byte[] bytes = Files.readAllBytes(p);
            // the ASM on this classpath is older than the class files (Java 25); their layout is the same for what is read
            // here, so the version is read as Java 21's
            if (bytes.length > 8 && bytes[7] > 65) {
                bytes[6] = 0;
                bytes[7] = 65;
            }
            {
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    String owner;

                    @Override
                    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                        owner = name;
                    }

                    @Override
                    public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                        if ((access & Opcodes.ACC_STATIC) != 0 && (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ENUM)) == 0) {
                            statics.put(owner + "#" + name, new Static(owner, name, desc, (access & Opcodes.ACC_FINAL) != 0));
                        }
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                        if (name.equals("<clinit>")) return null;
                        Set<String> read = new HashSet<>();
                        Set<String> calls = new HashSet<>();
                        boolean[] stores = new boolean[1];
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitFieldInsn(int opcode, String fOwner, String fName, String fDesc) {
                                if (opcode == Opcodes.PUTSTATIC) written.add(fOwner + "#" + fName);
                                if (opcode == Opcodes.GETSTATIC) read.add(fOwner + "#" + fName);
                            }

                            @Override
                            public void visitMethodInsn(int opcode, String mOwner, String mName, String mDesc, boolean itf) {
                                if (opcode != Opcodes.INVOKESTATIC && opcode != Opcodes.INVOKESPECIAL) calls.add(mOwner + "." + mName);
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE) stores[0] = true;
                            }

                            @Override
                            public void visitEnd() {
                                for (String f : read) {
                                    mutatedOwners.computeIfAbsent(f, k -> new HashSet<>()).addAll(calls);
                                    if (stores[0]) arrayStored.add(f);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        Set<String> out = new TreeSet<>();
        for (var e : statics.entrySet()) {
            Static s = e.getValue();
            String key = s.owner.replace('/', '.') + "#" + s.name;
            if (!s.isFinal) {
                if (written.contains(e.getKey())) out.add(key);
                continue;
            }
            Type t = Type.getType(s.desc);
            if (t.getSort() == Type.ARRAY) {
                if (arrayStored.contains(e.getKey())) out.add(key);
                continue;
            }
            if (t.getSort() != Type.OBJECT) continue;
            Class<?> type;
            try {
                type = Class.forName(t.getClassName(), false, loader);
            } catch (Throwable x) {
                continue;
            }
            Set<String> calls = mutatedOwners.getOrDefault(e.getKey(), Set.of());
            if (calls.isEmpty()) continue;
            boolean kind = Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)
                    || type.getName().startsWith("java.util.concurrent.atomic.") || type.getName().startsWith("it.unimi.dsi.fastutil.");
            if (kind) {
                if (mutates(calls, type, loader) && !immutableValue(key, loader)) out.add(key);
            } else if (ownStateful(type)) {
                // an object of our own with fields that change: any call on it may change them
                for (String c : calls) {
                    if (c.startsWith(t.getInternalName() + ".")) {
                        out.add(key);
                        break;
                    }
                }
            }
        }
        return out;
    }

    // a mutator called on a type the field's type is one of
    private static boolean mutates(Set<String> calls, Class<?> type, ClassLoader loader) {
        for (String c : calls) {
            int dot = c.lastIndexOf('.');
            if (!MUTATORS.contains(c.substring(dot + 1))) continue;
            try {
                Class<?> owner = Class.forName(c.substring(0, dot).replace('/', '.'), false, loader);
                if (owner.isAssignableFrom(type) || type.isAssignableFrom(owner)) return true;
            } catch (Throwable ignored) {
                return true;
            }
        }
        return false;
    }

    // the value an unmodifiable one (List.of, Collections.unmodifiable..., Guava's Immutable...): no state, whatever the
    // methods around it call. a class that will not initialise without a game counts as changeable
    private static boolean immutableValue(String key, ClassLoader loader) {
        try {
            Class<?> c = Class.forName(key.substring(0, key.indexOf('#')), true, loader);
            Field f = c.getDeclaredField(key.substring(key.indexOf('#') + 1));
            f.setAccessible(true);
            Object v = f.get(null);
            if (v == null) return false;
            String n = v.getClass().getName();
            return n.startsWith("java.util.ImmutableCollections$") || n.startsWith("java.util.Collections$Unmodifiable")
                    || n.startsWith("java.util.Collections$Empty") || n.startsWith("java.util.Collections$Singleton")
                    || n.startsWith("com.google.common.collect.Immutable") || n.startsWith("com.google.common.collect.Regular")
                    || n.startsWith("com.google.common.collect.Singleton");
        } catch (Throwable t) {
            return false;
        }
    }

    // a type of our own (the fork's or Baritone's) with an instance field that is not final, in it or a class above it
    private static boolean ownStateful(Class<?> type) {
        for (Class<?> c = type; c != null && (c.getName().startsWith("adris.") || c.getName().startsWith("baritone.")); c = c.getSuperclass()) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (!Modifier.isStatic(m) && !Modifier.isFinal(m)) return true;
                    // a final collection or map inside it is state as well
                    if (!Modifier.isStatic(m) && (Collection.class.isAssignableFrom(f.getType()) || Map.class.isAssignableFrom(f.getType()))) return true;
                }
            } catch (Throwable t) {
                return true;
            }
        }
        return false;
    }
}
