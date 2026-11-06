package baritone.utils.schematic.library;

import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.format.DefaultSchematicFormats;
import baritone.utils.schematic.format.ParseReport;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// everything in <gameDir>/schematics, parsed once per (path, mtime, size). parsing is slow for big files, so call
// list() off the render thread. the parsed schematic itself is only softly held, a 200^3 build is a lot of references
public final class SchematicLibrary {

    private final File directory;
    private final Map<String, Entry> cache = new HashMap<>();

    private static final class Entry {
        final long lastModified;
        final long size;
        final SchematicInfo info;
        SoftReference<IStaticSchematic> parsed;

        Entry(long lastModified, long size, SchematicInfo info, IStaticSchematic parsed) {
            this.lastModified = lastModified;
            this.size = size;
            this.info = info;
            this.parsed = new SoftReference<>(parsed);
        }
    }

    public SchematicLibrary(File directory) {
        this.directory = directory.getAbsoluteFile();
    }

    // same folder BuildCommand reads
    public static SchematicLibrary forGameDir(File gameDir) {
        return new SchematicLibrary(new File(gameDir, "schematics"));
    }

    public File directory() {
        return directory;
    }

    // recursive scan, sorted by relative path. files that fail to parse are still listed, with the error set
    public synchronized List<SchematicInfo> list() {
        List<File> files = scan();
        List<SchematicInfo> out = new ArrayList<>(files.size());
        Set<String> seen = new HashSet<>();
        for (File file : files) {
            String key = file.getAbsolutePath();
            seen.add(key);
            Entry entry = cache.get(key);
            if (entry == null || entry.lastModified != file.lastModified() || entry.size != file.length()) {
                entry = read(file);
                cache.put(key, entry);
            }
            out.add(entry.info);
        }
        // deleted files shouldn't pin their parsed schematics
        cache.keySet().retainAll(seen);
        out.sort(Comparator.comparing(i -> i.relativePath().toLowerCase(Locale.ROOT)));
        return out;
    }

    // the parsed schematic for a listed file, from the cache when it's still alive
    public synchronized IStaticSchematic load(SchematicInfo info) throws IOException {
        File file = info.file();
        Entry entry = cache.get(file.getAbsolutePath());
        if (entry != null && entry.lastModified == file.lastModified() && entry.size == file.length()) {
            IStaticSchematic alive = entry.parsed.get();
            if (alive != null) {
                return alive;
            }
        }
        ISchematicFormat format = SchematicSystem.INSTANCE.getByFile(file)
            .orElseThrow(() -> new IOException("not a schematic file: " + file.getName()));
        IStaticSchematic parsed;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            parsed = format.parse(in);
        }
        if (entry != null) {
            entry.parsed = new SoftReference<>(parsed);
        }
        return parsed;
    }

    public synchronized void clearCache() {
        cache.clear();
    }

    private List<File> scan() {
        if (!directory.isDirectory()) {
            return Collections.emptyList();
        }
        List<File> found = new ArrayList<>();
        try {
            Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    File file = path.toFile();
                    if (attrs.isRegularFile() && SchematicSystem.INSTANCE.getByFile(file).isPresent()) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path path, IOException e) {
                    // one folder we can't read shouldn't hide all the others
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            // whatever was found before it went wrong is still good
        }
        return found;
    }

    private Entry read(File file) {
        long modified = file.lastModified();
        long size = file.length();
        String relative = directory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        String name = FilenameUtils.removeExtension(file.getName());
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(file);
        String formatName = format.map(SchematicLibrary::formatName).orElse("unknown");
        IStaticSchematic parsed = null;
        SchematicInfo info;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            parsed = format.orElseThrow(() -> new IOException("no format for this file")).parse(in);
            info = summarize(file, name, relative, size, modified, formatName, parsed);
        } catch (Exception | OutOfMemoryError | StackOverflowError e) {
            // a huge file can run the heap dry, that's this row's problem and not the whole tab's
            info = new SchematicInfo(file, name, relative, size, modified, formatName, -1, 0, 0, 0, 0,
                List.of(), describe(e));
            parsed = null;
        }
        return new Entry(modified, size, info, parsed);
    }

    private static SchematicInfo summarize(File file, String name, String relative, long size, long modified,
                                           String formatName, IStaticSchematic parsed) {
        int version = 0;
        List<SchematicInfo.UnknownBlock> unknown = new ArrayList<>();
        if (parsed instanceof ParseReport report) {
            version = report.formatVersion();
            report.unknownBlocks().forEach((id, count) -> unknown.add(new SchematicInfo.UnknownBlock(id, count)));
            unknown.sort(Comparator.comparingInt(SchematicInfo.UnknownBlock::count).reversed()
                .thenComparing(SchematicInfo.UnknownBlock::id));
        }
        return new SchematicInfo(file, name, relative, size, modified, formatName, version,
            parsed.widthX(), parsed.heightY(), parsed.lengthZ(), countNonAir(parsed),
            Collections.unmodifiableList(unknown), null);
    }

    // walks inSchematic first because a multi region litematic has gaps that desiredState would throw on
    static long countNonAir(ISchematic schematic) {
        long count = 0;
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    if (!schematic.inSchematic(x, y, z, null)) {
                        continue;
                    }
                    BlockState state = schematic.desiredState(x, y, z, null, Collections.emptyList());
                    if (state != null && !state.isAir()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static String formatName(ISchematicFormat format) {
        if (format instanceof DefaultSchematicFormats known) {
            return switch (known) {
                case MCEDIT -> "MCEdit";
                case SPONGE -> "Sponge";
                case LITEMATICA -> "Litematica";
            };
        }
        return format.getClass().getSimpleName();
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        // our own "unsupported version" messages read fine alone, everything else gets the exception name so a
        // NoSuchElementException doesn't show up as just "No value present"
        if (e instanceof OutOfMemoryError) {
            return "too big to load (out of memory)";
        }
        if (e instanceof UnsupportedOperationException && message != null) {
            return message;
        }
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
