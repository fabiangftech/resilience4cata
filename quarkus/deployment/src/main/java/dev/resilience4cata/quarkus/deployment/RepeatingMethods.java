package dev.resilience4cata.quarkus.deployment;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The methods of a generated subclass that run their call more than once: those with a {@code @Retry} or a
 * {@code @Hedge} layer, wherever the annotation was declared (method, class, superclass, interface, bundle).
 *
 * <p>The annotations have {@code CLASS} retention, so they cannot be read at run time; what the processor
 * decided is in the generated code instead. Retry and hedge are the outermost layers after the fallback, which
 * is fused into the retry, so an override that has one of them reads its {@code RetryPolicy} or
 * {@code HedgePolicy} field in its own body ({@code final RetryPolicy r4c$retry = this.retry0;} for a sync
 * method, {@code this.hedge0.executeAsync(...)} for an async one), or in the lambda it returns for a {@code Mono}
 * method ({@code Monos.defer(c -> this.retry0.executeAsync(...))}), and an override without them does not. This
 * class finds those field reads in the class file, by walking the bytecode of each method instruction by
 * instruction and following its {@code invokedynamic}s to the lambda bodies of the same class (through the
 * {@code BootstrapMethods} attribute). Returns an empty map when the class file is not readable (a native image has
 * none).
 */
final class RepeatingMethods {

    private static final String RETRY = "Ldev/resilience4cata/generated/RetryPolicy;";
    private static final String HEDGE = "Ldev/resilience4cata/generated/HedgePolicy;";
    private static final int GETFIELD = 0xb4;
    private static final int INVOKEDYNAMIC = 0xba;

    private RepeatingMethods() {
    }

    /**
     * @param loader the loader that sees the class file
     * @param generated the binary name of a generated subclass
     * @return {@code name + descriptor} of each method with a retry or hedge layer, and {@code "@Retry"} or
     *     {@code "@Hedge"}; empty if the class file cannot be read
     */
    static Map<String, String> of(ClassLoader loader, String generated) {
        String internal = generated.replace('.', '/');
        try (InputStream in = loader.getResourceAsStream(internal + ".class")) {
            return in == null ? Map.of() : read(new DataInputStream(in), internal);
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    /** {@code name + descriptor} of each method whose code reads a retry or hedge field of {@code owner}. */
    private static Map<String, String> read(DataInputStream in, String owner) throws IOException {
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major
        ConstantPool pool = ConstantPool.read(in);
        in.readUnsignedShort(); // access
        in.readUnsignedShort(); // this
        in.readUnsignedShort(); // super
        in.skipBytes(2 * in.readUnsignedShort()); // interfaces
        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            in.skipBytes(6);
            skipAttributes(in);
        }
        Map<String, String> direct = new HashMap<>();
        Map<String, List<Integer>> bootstraps = new HashMap<>();
        int methods = in.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            in.readUnsignedShort(); // access
            String name = pool.utf8(in.readUnsignedShort());
            String descriptor = pool.utf8(in.readUnsignedShort());
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                String attribute = pool.utf8(in.readUnsignedShort());
                int length = in.readInt();
                if (!attribute.equals("Code")) {
                    in.skipBytes(length);
                    continue;
                }
                in.skipBytes(4); // max_stack, max_locals
                byte[] code = new byte[in.readInt()];
                in.readFully(code);
                in.skipBytes(length - 8 - code.length);
                String policy = policyRead(code, pool, owner);
                if (policy != null) {
                    direct.put(name + descriptor, policy);
                }
                bootstraps.put(name + descriptor, invokedynamics(code, pool));
            }
        }
        List<List<String>> lambdas = bootstrapMethods(in, pool, owner);
        Map<String, String> found = new HashMap<>();
        for (String method : bootstraps.keySet()) {
            String policy = reached(method, direct, bootstraps, lambdas, new HashSet<>());
            if (policy != null) {
                found.put(method, policy);
            }
        }
        return found;
    }

    /** The policy {@code method} reads, itself or in a lambda of this class that it creates, or {@code null}. */
    private static @Nullable String reached(String method, Map<String, String> direct, Map<String, List<Integer>> bootstraps,
            List<List<String>> lambdas, Set<String> visited) {
        if (!visited.add(method)) {
            return null;
        }
        String policy = direct.get(method);
        if (policy != null) {
            return policy;
        }
        for (int bootstrap : bootstraps.getOrDefault(method, List.of())) {
            if (bootstrap < lambdas.size()) {
                for (String lambda : lambdas.get(bootstrap)) {
                    policy = reached(lambda, direct, bootstraps, lambdas, visited);
                    if (policy != null) {
                        return policy;
                    }
                }
            }
        }
        return null;
    }

    /**
     * For each entry of the class's {@code BootstrapMethods} attribute, the methods of {@code owner} its arguments
     * refer to ({@code name + descriptor}): for a lambda, its body. Reads the class attributes, which follow the
     * methods.
     */
    private static List<List<String>> bootstrapMethods(DataInputStream in, ConstantPool pool, String owner)
            throws IOException {
        int attributes = in.readUnsignedShort();
        for (int a = 0; a < attributes; a++) {
            String attribute = pool.utf8(in.readUnsignedShort());
            int length = in.readInt();
            if (!attribute.equals("BootstrapMethods")) {
                in.skipBytes(length);
                continue;
            }
            int count = in.readUnsignedShort();
            List<List<String>> methods = new ArrayList<>(count);
            for (int b = 0; b < count; b++) {
                in.readUnsignedShort(); // the bootstrap method itself (LambdaMetafactory)
                int arguments = in.readUnsignedShort();
                List<String> targets = new ArrayList<>();
                for (int i = 0; i < arguments; i++) {
                    String target = pool.methodOf(in.readUnsignedShort(), owner);
                    if (target != null) {
                        targets.add(target);
                    }
                }
                methods.add(targets);
            }
            return methods;
        }
        return List.of();
    }

    /** The bootstrap method indexes of the {@code invokedynamic} instructions in {@code code}. */
    private static List<Integer> invokedynamics(byte[] code, ConstantPool pool) {
        List<Integer> found = new ArrayList<>();
        int pc = 0;
        while (pc < code.length) {
            int opcode = code[pc] & 0xff;
            if (opcode == INVOKEDYNAMIC) {
                int bootstrap = pool.bootstrapOf(((code[pc + 1] & 0xff) << 8) | (code[pc + 2] & 0xff));
                if (bootstrap >= 0) {
                    found.add(bootstrap);
                }
            }
            pc += length(code, pc, opcode);
        }
        return found;
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int attributes = in.readUnsignedShort();
        for (int a = 0; a < attributes; a++) {
            in.skipBytes(2);
            in.skipBytes(in.readInt());
        }
    }

    /** {@code "@Retry"} or {@code "@Hedge"} if the code reads such a field of {@code owner}, else {@code null}. */
    private static @Nullable String policyRead(byte[] code, ConstantPool pool, String owner) {
        int pc = 0;
        while (pc < code.length) {
            int opcode = code[pc] & 0xff;
            if (opcode == GETFIELD) {
                String type = pool.fieldOf(((code[pc + 1] & 0xff) << 8) | (code[pc + 2] & 0xff), owner);
                if (RETRY.equals(type)) {
                    return "@Retry";
                }
                if (HEDGE.equals(type)) {
                    return "@Hedge";
                }
            }
            pc += length(code, pc, opcode);
        }
        return null;
    }

    /** The length of the instruction at {@code pc}, operands included (JVMS chapter 6). */
    private static int length(byte[] code, int pc, int opcode) {
        if (opcode == 0xaa || opcode == 0xab) { // tableswitch, lookupswitch: padded to 4 bytes from the code start
            int base = (pc + 4) & ~3;
            if (opcode == 0xaa) {
                int low = readInt(code, base + 4);
                int high = readInt(code, base + 8);
                return base - pc + 12 + 4 * (high - low + 1);
            }
            return base - pc + 8 + 8 * readInt(code, base + 4);
        }
        if (opcode == 0xc4) { // wide
            return (code[pc + 1] & 0xff) == 0x84 ? 6 : 4;
        }
        if (opcode == 0x10 || opcode == 0x12 || (opcode >= 0x15 && opcode <= 0x19) || (opcode >= 0x36 && opcode <= 0x3a)
                || opcode == 0xa9 || opcode == 0xbc) {
            return 2;
        }
        if (opcode == 0x11 || opcode == 0x13 || opcode == 0x14 || opcode == 0x84 || (opcode >= 0x99 && opcode <= 0xa8)
                || (opcode >= 0xb2 && opcode <= 0xb8) || opcode == 0xbb || opcode == 0xbd || opcode == 0xc0
                || opcode == 0xc1 || opcode == 0xc6 || opcode == 0xc7) {
            return 3;
        }
        if (opcode == 0xc5) {
            return 4;
        }
        if (opcode == 0xb9 || opcode == 0xba || opcode == 0xc8 || opcode == 0xc9) {
            return 5;
        }
        return 1;
    }

    private static int readInt(byte[] code, int at) {
        return ((code[at] & 0xff) << 24) | ((code[at + 1] & 0xff) << 16) | ((code[at + 2] & 0xff) << 8) | (code[at + 3] & 0xff);
    }

    /**
     * The parts of the constant pool this reader needs: UTF-8 entries, field and method references and their names
     * and types, method handles and {@code invokedynamic} entries.
     */
    private record ConstantPool(Object[] entries) {

        private static final int UTF8 = 1;
        private static final int CLASS = 7;
        private static final int FIELDREF = 9;
        private static final int METHODREF = 10;
        private static final int INTERFACE_METHODREF = 11;
        private static final int NAME_AND_TYPE = 12;
        private static final int METHOD_HANDLE = 15;
        private static final int INVOKE_DYNAMIC = 18;

        static ConstantPool read(DataInputStream in) throws IOException {
            Object[] entries = new Object[in.readUnsignedShort()];
            for (int i = 1; i < entries.length; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case UTF8 -> entries[i] = in.readUTF();
                    case CLASS, 8, 16, 19, 20 -> entries[i] = new int[] {tag, in.readUnsignedShort()};
                    case 3, 4 -> in.skipBytes(4);
                    case 5, 6 -> {
                        in.skipBytes(8);
                        i++; // takes two entries
                    }
                    case FIELDREF, METHODREF, INTERFACE_METHODREF, NAME_AND_TYPE, 17, INVOKE_DYNAMIC ->
                            entries[i] = new int[] {tag, in.readUnsignedShort(), in.readUnsignedShort()};
                    case METHOD_HANDLE -> entries[i] = new int[] {tag, in.readUnsignedByte(), in.readUnsignedShort()};
                    default -> throw new IOException("unknown constant pool tag " + tag);
                }
            }
            return new ConstantPool(entries);
        }

        String utf8(int index) {
            return (String) entries[index];
        }

        /** The descriptor of the field that entry {@code index} refers to, if it is a field of {@code owner}. */
        @Nullable String fieldOf(int index, String owner) {
            if (!(entries[index] instanceof int[] ref) || ref[0] != FIELDREF) {
                return null;
            }
            int[] type = (int[]) entries[ref[1]];
            if (!owner.equals(utf8(type[1]))) {
                return null;
            }
            int[] nameAndType = (int[]) entries[ref[2]];
            return utf8(nameAndType[2]);
        }

        /**
         * {@code name + descriptor} of the method that the method handle at {@code index} refers to, if it is a
         * method of {@code owner}; {@code null} for another entry or another class.
         */
        @Nullable String methodOf(int index, String owner) {
            if (!(entries[index] instanceof int[] handle) || handle[0] != METHOD_HANDLE
                    || !(entries[handle[2]] instanceof int[] ref) || (ref[0] != METHODREF && ref[0] != INTERFACE_METHODREF)) {
                return null;
            }
            int[] type = (int[]) entries[ref[1]];
            if (!owner.equals(utf8(type[1]))) {
                return null;
            }
            int[] nameAndType = (int[]) entries[ref[2]];
            return utf8(nameAndType[1]) + utf8(nameAndType[2]);
        }

        /** The bootstrap method index of the {@code invokedynamic} entry at {@code index}, or {@code -1}. */
        int bootstrapOf(int index) {
            return entries[index] instanceof int[] ref && ref[0] == INVOKE_DYNAMIC ? ref[1] : -1;
        }
    }
}
