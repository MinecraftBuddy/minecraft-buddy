package baritone.utils.schematic.library;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// tiny schematic files built out of nbt, so no binary fixtures get checked in. palette specs are "namespace:name" or
// "namespace:name[key=value,key=value]", which is also exactly how a sponge palette writes them
public final class SchematicFixtures {

    private SchematicFixtures() {
    }

    // for tests in other packages (the gui facade): a sponge v3 file with the given palette and block ids
    public static Path writeSponge(Path file, int w, int h, int l, String[] palette, int[] ids) throws IOException {
        return write(file, spongeV3(w, h, l, palette, ids));
    }

    static byte[] gzip(CompoundTag root) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Path write(Path file, CompoundTag root) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, gzip(root));
        return file;
    }

    // x fastest, then z, then y: the order all three formats use
    static int index(int x, int y, int z, int sx, int sz) {
        return (y * sz + z) * sx + x;
    }

    static CompoundTag spongeV2(int w, int h, int l, String[] palette, int[] ids) {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("DataVersion", 4000);
        putSize(root, w, h, l);
        root.put("Palette", spongePalette(palette));
        root.putByteArray("BlockData", varints(ids));
        return root;
    }

    // v3 nests everything under "Schematic", with the blocks in their own compound and BlockData renamed to Data
    static CompoundTag spongeV3(int w, int h, int l, String[] palette, int[] ids) {
        CompoundTag inner = new CompoundTag();
        inner.putInt("Version", 3);
        inner.putInt("DataVersion", 4000);
        putSize(inner, w, h, l);
        CompoundTag blocks = new CompoundTag();
        blocks.put("Palette", spongePalette(palette));
        blocks.putByteArray("Data", varints(ids));
        inner.put("Blocks", blocks);
        CompoundTag root = new CompoundTag();
        root.put("Schematic", inner);
        return root;
    }

    private static void putSize(CompoundTag tag, int w, int h, int l) {
        // the spec says shorts
        tag.putShort("Width", (short) w);
        tag.putShort("Height", (short) h);
        tag.putShort("Length", (short) l);
    }

    private static CompoundTag spongePalette(String[] palette) {
        CompoundTag tag = new CompoundTag();
        for (int i = 0; i < palette.length; i++) {
            tag.putInt(palette[i], i);
        }
        return tag;
    }

    static byte[] varints(int[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int value : values) {
            int v = value;
            while ((v & -128) != 0) {
                out.write(v & 127 | 128);
                v >>>= 7;
            }
            out.write(v);
        }
        return out.toByteArray();
    }

    static CompoundTag mcedit(int w, int h, int l, int[] blockIds) {
        CompoundTag root = new CompoundTag();
        root.putString("Materials", "Alpha");
        putSize(root, w, h, l);
        byte[] blocks = new byte[blockIds.length];
        boolean needsAdd = false;
        for (int i = 0; i < blockIds.length; i++) {
            blocks[i] = (byte) blockIds[i];
            needsAdd |= blockIds[i] > 255;
        }
        root.putByteArray("Blocks", blocks);
        root.putByteArray("Data", new byte[blockIds.length]);
        if (needsAdd) {
            // the parser reads the nibbles in the opposite order from the spec (high nibble first), match it
            byte[] add = new byte[(blockIds.length + 1) / 2];
            for (int i = 0; i < blockIds.length; i++) {
                int high = blockIds[i] >> 8;
                add[i / 2] |= (byte) (i % 2 == 0 ? high << 4 : high);
            }
            root.putByteArray("AddBlocks", add);
        }
        return root;
    }

    // one region at the origin
    static CompoundTag litematic(int version, int sx, int sy, int sz, String[] palette, int[] ids) {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", version);
        root.putInt("MinecraftDataVersion", 3700);
        CompoundTag regions = new CompoundTag();
        regions.put("main", litematicRegion(0, 0, 0, sx, sy, sz, palette, ids));
        root.put("Regions", regions);
        return root;
    }

    static CompoundTag litematicRegion(int px, int py, int pz, int sx, int sy, int sz, String[] palette, int[] ids) {
        CompoundTag region = new CompoundTag();
        region.put("Position", xyz(px, py, pz));
        region.put("Size", xyz(sx, sy, sz));
        ListTag list = new ListTag();
        for (String spec : palette) {
            list.add(litematicEntry(spec));
        }
        region.put("BlockStatePalette", list);
        region.putLongArray("BlockStates", pack(ids, palette.length));
        return region;
    }

    private static CompoundTag xyz(int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }

    private static CompoundTag litematicEntry(String spec) {
        CompoundTag entry = new CompoundTag();
        int bracket = spec.indexOf('[');
        if (bracket < 0) {
            entry.putString("Name", spec);
            return entry;
        }
        entry.putString("Name", spec.substring(0, bracket));
        CompoundTag props = new CompoundTag();
        for (String pair : spec.substring(bracket + 1, spec.length() - 1).split(",")) {
            String[] kv = pair.split("=");
            props.putString(kv[0], kv[1]);
        }
        entry.put("Properties", props);
        return entry;
    }

    // tightly packed, entries are allowed to straddle two longs. same layout litematica writes
    static long[] pack(int[] ids, int paletteSize) {
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
        long[] out = new long[(int) ((ids.length * (long) bits + 63) / 64)];
        for (int i = 0; i < ids.length; i++) {
            long start = (long) i * bits;
            int word = (int) (start >> 6);
            int offset = (int) (start & 63);
            out[word] |= ((long) ids[i]) << offset;
            if (offset + bits > 64) {
                out[word + 1] |= ((long) ids[i]) >>> (64 - offset);
            }
        }
        return out;
    }
}
