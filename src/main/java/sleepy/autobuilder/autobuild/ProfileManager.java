package sleepy.autobuilder.autobuild;

import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import sleepy.autobuilder.SevenBuilderAddon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Stores Auto Builder settings as standalone NBT profiles.
 *
 * <p>Profiles live in {@code meteor-client/seven-builder/profiles} and are completely separate
 * from Meteor's global profiles, so loading one never changes unrelated modules. Export/import copy
 * the same NBT format to an arbitrary path.</p>
 */
public final class ProfileManager {
    public record Result(boolean ok, String message) {
    }

    private static final int FORMAT = 1;
    private static final ProfileManager INSTANCE = new ProfileManager();

    private final Path directory = MeteorClient.FOLDER.toPath()
            .resolve("seven-builder").resolve("profiles");
    private AutoBuilder module;

    private ProfileManager() {
    }

    public static ProfileManager get() {
        return INSTANCE;
    }

    public void init(AutoBuilder module) {
        this.module = module;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            SevenBuilderAddon.LOG.warn("Could not create Auto Builder profile directory {}", directory, e);
        }
    }

    public Path directory() {
        return directory;
    }

    public List<String> list() {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(directory)) return names;
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".nbt"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .sorted(Comparator.comparing(String::toLowerCase, String.CASE_INSENSITIVE_ORDER))
                    .forEach(names::add);
        } catch (IOException e) {
            SevenBuilderAddon.LOG.warn("Could not list Auto Builder profiles", e);
        }
        return names;
    }

    public Result save(String requestedName) {
        String key = key(requestedName);
        if (key == null) return invalidName(requestedName);
        AutoBuilder target = requireModule();
        if (target == null) return noModule();
        try {
            Files.createDirectories(directory);
            write(file(key), target);
            return new Result(true, "Saved profile '" + key + "'.");
        } catch (IOException | RuntimeException e) {
            return new Result(false, "Could not save profile '" + key + "': " + reason(e));
        }
    }

    public Result load(String requestedName) {
        String key = key(requestedName);
        if (key == null) return invalidName(requestedName);
        AutoBuilder target = requireModule();
        if (target == null) return noModule();
        Path file = file(key);
        if (!Files.isRegularFile(file)) return new Result(false, "Profile '" + key + "' does not exist.");
        try {
            NbtCompound root = read(file);
            NbtCompound settings = root.contains("settings", NbtElement.COMPOUND_TYPE)
                    ? root.getCompound("settings") : root;
            target.settings.fromTag(settings);
            AutoBuildEngine.get().invalidateRules();
            AutoBuildEngine.get().invalidateJob();
            return new Result(true, "Loaded profile '" + key + "'.");
        } catch (IOException | RuntimeException e) {
            return new Result(false, "Could not load profile '" + key + "': " + reason(e));
        }
    }

    public Result duplicate(String fromName, String toName) {
        String from = key(fromName);
        String to = key(toName);
        if (from == null) return invalidName(fromName);
        if (to == null) return invalidName(toName);
        Path source = file(from);
        if (!Files.isRegularFile(source)) return new Result(false, "Profile '" + from + "' does not exist.");
        if (Files.exists(file(to))) return new Result(false, "Profile '" + to + "' already exists.");
        try {
            Files.copy(source, file(to), StandardCopyOption.COPY_ATTRIBUTES);
            return new Result(true, "Duplicated profile '" + from + "' as '" + to + "'.");
        } catch (IOException e) {
            return new Result(false, "Could not duplicate profile: " + reason(e));
        }
    }

    public Result rename(String fromName, String toName) {
        String from = key(fromName);
        String to = key(toName);
        if (from == null) return invalidName(fromName);
        if (to == null) return invalidName(toName);
        Path source = file(from);
        if (!Files.isRegularFile(source)) return new Result(false, "Profile '" + from + "' does not exist.");
        if (Files.exists(file(to))) return new Result(false, "Profile '" + to + "' already exists.");
        try {
            Files.move(source, file(to));
            return new Result(true, "Renamed profile '" + from + "' to '" + to + "'.");
        } catch (IOException e) {
            return new Result(false, "Could not rename profile: " + reason(e));
        }
    }

    public Result delete(String requestedName) {
        String key = key(requestedName);
        if (key == null) return invalidName(requestedName);
        try {
            if (!Files.deleteIfExists(file(key))) {
                return new Result(false, "Profile '" + key + "' does not exist.");
            }
            return new Result(true, "Deleted profile '" + key + "'.");
        } catch (IOException e) {
            return new Result(false, "Could not delete profile '" + key + "': " + reason(e));
        }
    }

    public Result reset() {
        AutoBuilder target = requireModule();
        if (target == null) return noModule();
        target.settings.reset();
        AutoBuildEngine.get().invalidateRules();
        AutoBuildEngine.get().invalidateJob();
        return new Result(true, "Reset every Auto Builder setting to its default.");
    }

    public Result exportProfile(String requestedName, String rawTarget) {
        String key = key(requestedName);
        if (key == null) return invalidName(requestedName);
        Path source = file(key);
        if (!Files.isRegularFile(source)) return new Result(false, "Profile '" + key + "' does not exist.");
        Path target = externalPath(rawTarget, key);
        if (target == null) return new Result(false, "Invalid export path.");
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return new Result(true, "Exported profile '" + key + "' to " + target + ".");
        } catch (IOException e) {
            return new Result(false, "Could not export profile: " + reason(e));
        }
    }

    public Result importProfile(String rawSource, String requestedName) {
        Path source = externalPath(rawSource, null);
        if (source == null || !Files.isRegularFile(source)) {
            return new Result(false, "Import file does not exist: " + rawSource);
        }
        String base = requestedName;
        if (base == null || base.isBlank()) {
            String fileName = source.getFileName().toString();
            base = fileName.toLowerCase(Locale.ROOT).endsWith(".nbt")
                    ? fileName.substring(0, fileName.length() - 4) : fileName;
        }
        String key = key(base);
        if (key == null) return invalidName(base);
        AutoBuilder target = requireModule();
        if (target == null) return noModule();
        try {
            NbtCompound root = read(source);
            NbtCompound settings = root.contains("settings", NbtElement.COMPOUND_TYPE)
                    ? root.getCompound("settings") : root;
            Files.createDirectories(directory);
            write(file(key), settings);
            target.settings.fromTag(settings);
            AutoBuildEngine.get().invalidateRules();
            AutoBuildEngine.get().invalidateJob();
            return new Result(true, "Imported profile '" + key + "' from " + source + ".");
        } catch (IOException | RuntimeException e) {
            return new Result(false, "Could not import profile: " + reason(e));
        }
    }

    private void write(Path file, AutoBuilder target) throws IOException {
        NbtCompound root = new NbtCompound();
        root.putInt("format", FORMAT);
        root.putString("name", file.getFileName().toString().replaceFirst("(?i)\\.nbt$", ""));
        root.put("settings", target.settings.toTag());
        NbtIo.writeCompressed(root, file);
    }

    private void write(Path file, NbtCompound settings) throws IOException {
        NbtCompound root = new NbtCompound();
        root.putInt("format", FORMAT);
        root.putString("name", file.getFileName().toString().replaceFirst("(?i)\\.nbt$", ""));
        root.put("settings", settings);
        NbtIo.writeCompressed(root, file);
    }

    private NbtCompound read(Path file) throws IOException {
        return NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
    }

    private AutoBuilder requireModule() {
        if (module != null) return module;
        return meteordevelopment.meteorclient.systems.modules.Modules.get().get(AutoBuilder.class);
    }

    private Result noModule() {
        return new Result(false, "Auto Builder module is not registered.");
    }

    private Result invalidName(String name) {
        return new Result(false, "Invalid profile name '" + name
                + "'. Use 1-48 letters, digits, spaces, '-', '_' or '.' and do not start with '.'.");
    }

    private Path file(String key) {
        return directory.resolve(key + ".nbt");
    }

    private Path externalPath(String raw, String fallbackName) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) path = MeteorClient.FOLDER.toPath().resolve(path);
            if (Files.isDirectory(path) || value.endsWith("/") || value.endsWith("\\")) {
                if (fallbackName == null) return null;
                path = path.resolve(fallbackName + ".nbt");
            } else if (fallbackName != null
                    && !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbt")) {
                path = path.resolveSibling(path.getFileName() + ".nbt");
            }
            return path.normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String key(String requestedName) {
        if (requestedName == null) return null;
        String name = requestedName.trim();
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
            name = name.substring(1, name.length() - 1).trim();
        }
        if (name.isEmpty() || name.length() > 48) return null;
        if (name.startsWith(".") || name.contains("..")) return null;
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.';
            if (c == ' ') {
                out.append('_');
            } else if (allowed) {
                out.append(c);
            } else {
                return null;
            }
        }
        String key = out.toString();
        return key.isBlank() ? null : key;
    }

    private static String reason(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
    }
}
