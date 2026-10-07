package dev.avelar.mapnik;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds and loads {@code libmapnik_c}. In order of preference:
 * <ol>
 *   <li>The directory in the system property {@code mapnik.native.dir} or the environment variable
 *       {@code MAPNIK_JAVA_NATIVE_DIR}.</li>
 *   <li>A directory already given to JNA in {@code jna.library.path} that holds the library.</li>
 *   <li>A bundle of native libraries on the class path (the {@code mapnik-java-natives-*} artifact):
 *       it is unpacked once to a cache directory and loaded from there, with Mapnik, its dependencies,
 *       plugins, fonts and PROJ data all inside it.</li>
 *   <li>The system's library search path, which needs a Mapnik install and the shim built for it.</li>
 * </ol>
 */
final class NativeLoader {
    static final String RESOURCE_ROOT = "dev/avelar/mapnik/natives/";
    private static final String LIBRARY_NAME = "mapnik_c";

    private static volatile Path bundleDir;

    private NativeLoader() {}

    /** Where bundled natives were unpacked, or null if the system or an explicit directory is used. */
    static Path bundleDirectory() {
        return bundleDir;
    }

    static NativeApi load() {
        Path explicit = explicitDirectory();
        Path bundled = null;
        if (explicit != null) {
            NativeLibrary.addSearchPath(LIBRARY_NAME, explicit.toString());
        } else if (!jnaPathHasLibrary()) {
            bundled = prepareBundle();
            if (bundled != null) {
                NativeLibrary.addSearchPath(LIBRARY_NAME, bundled.resolve("lib").toString());
            }
        }
        NativeApi api;
        try {
            api = Native.load(LIBRARY_NAME, NativeApi.class,
                Collections.singletonMap(Library.OPTION_STRING_ENCODING, "UTF-8"));
        } catch (UnsatisfiedLinkError e) {
            if (bundled == null) {
                throw e;
            }
            throw new UnsatisfiedLinkError("the bundled native libraries in " + bundled + " could not be loaded: "
                + e.getMessage() + ". " + requirements());
        }
        if (bundled != null) {
            setUpBundle(api, bundled);
            bundleDir = bundled;
        }
        return api;
    }

    private static String requirements() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac")) {
            return "The bundle needs the macOS version named in its NOTICE file or a later one; on an older system "
                + "build the shim against a Mapnik of your own.";
        }
        return "The bundle is built on Ubuntu 24.04 and needs glibc 2.39 or later and the C++ runtime of GCC 13 or "
            + "later; on an older system build the shim against a Mapnik of your own.";
    }

    // ------------------------------------------------------------------ choosing where to load from

    private static Path explicitDirectory() {
        String dir = System.getProperty("mapnik.native.dir");
        if (dir == null || dir.isEmpty()) {
            dir = System.getenv("MAPNIK_JAVA_NATIVE_DIR");
        }
        return dir == null || dir.isEmpty() ? null : Paths.get(dir);
    }

    private static boolean jnaPathHasLibrary() {
        String path = System.getProperty("jna.library.path");
        if (path == null || path.isEmpty()) {
            return false;
        }
        String file = System.mapLibraryName(LIBRARY_NAME);
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isEmpty() && Files.exists(Paths.get(dir, file))) {
                return true;
            }
        }
        return false;
    }

    /** Unpack the bundle that matches this platform, or return null if the class path has none. */
    private static Path prepareBundle() {
        String platform = platformOf(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        if (platform == null) {
            return null;
        }
        final String root = RESOURCE_ROOT + platform + "/";
        final ClassLoader loader = NativeLoader.class.getClassLoader();
        ResourceSource source = path -> {
            InputStream in = loader.getResourceAsStream(root + path);
            if (in == null) {
                throw new IOException("missing from the natives bundle: " + root + path);
            }
            return in;
        };
        if (loader.getResource(root + "MANIFEST") == null) {
            return null;
        }
        try {
            return extract(source, cacheRoot());
        } catch (IOException e) {
            throw new UncheckedIOException("could not unpack the bundled native libraries", e);
        }
    }

    /** After loading a bundle: tell PROJ where its data is, and register the bundled plugins and fonts. */
    private static void setUpBundle(NativeApi api, Path bundle) {
        // NativeApi.INSTANCE is not assigned yet, so check with the api we were given.
        check(api, api.mapnik_set_environment(bundle.resolve("proj").toString()));
        check(api, api.mapnik_register_datasources(bundle.resolve("plugins").resolve("input").toString()));
        check(api, api.mapnik_register_fonts(bundle.resolve("fonts").toString()));
    }

    private static void check(NativeApi api, int rc) {
        if (rc != 0) {
            throw new MapnikException("setting up the bundled natives failed: " + api.mapnik_last_error());
        }
    }

    // ------------------------------------------------------------------ platform

    /**
     * The name of the bundle for an operating system and architecture, such as {@code linux-x86_64},
     * or null if none is published.
     */
    static String platformOf(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        String a;
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) {
            a = "x86_64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            a = "aarch64";
        } else {
            return null;
        }
        if (os.startsWith("linux")) {
            return "linux-" + a;
        }
        if (os.startsWith("mac") || os.startsWith("darwin")) {
            return "macos-" + a;
        }
        if (os.startsWith("windows")) {
            return "windows-" + a;
        }
        return null;
    }

    // ------------------------------------------------------------------ unpacking

    /** Opens files of a bundle by their path inside it. */
    interface ResourceSource {
        InputStream open(String path) throws IOException;
    }

    private static Path cacheRoot() {
        String configured = System.getProperty("mapnik.native.cache");
        if (configured != null && !configured.isEmpty()) {
            return Paths.get(configured);
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isEmpty()) {
            return Paths.get(xdg, "mapnik-java");
        }
        return Paths.get(System.getProperty("user.home", "."), ".cache", "mapnik-java");
    }

    private static final class Entry {
        final String path;
        final long size;
        final String sha256;

        Entry(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /**
     * Unpack a bundle under {@code cacheRoot}, in a directory named after a hash of its manifest, and
     * return it. A directory that was unpacked completely before is reused. Every file is checked
     * against the manifest's size and SHA-256. If the cache root is not safe to load code from (owned by
     * someone else, or writable by other users) a private temporary directory is used instead.
     */
    static Path extract(ResourceSource source, Path cacheRoot) throws IOException {
        String manifest = read(source, "MANIFEST");
        List<Entry> entries = parseManifest(manifest);
        String id = sha256(manifest.getBytes(StandardCharsets.UTF_8)).substring(0, 16);

        Path root = safeRoot(cacheRoot);
        boolean temporary = root == null;
        if (temporary) {
            root = Files.createTempDirectory("mapnik-java-");
            final Path toDelete = root;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteQuietly(toDelete)));
        }
        Path target = root.resolve(id);
        if (Files.exists(target.resolve(".complete"))) {
            return target;
        }

        Path staging = Files.createTempDirectory(root, id + ".tmp-");
        try {
            for (Entry e : entries) {
                copyVerified(source, e, staging);
            }
            Files.write(staging.resolve("MANIFEST"), manifest.getBytes(StandardCharsets.UTF_8));
            Files.write(staging.resolve(".complete"), new byte[0]);
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException ex) {
                if (!Files.exists(target.resolve(".complete"))) {
                    throw ex;
                }
            } catch (IOException ex) {
                // Another process won the race and its directory is not empty: use theirs.
                if (!Files.exists(target.resolve(".complete"))) {
                    throw ex;
                }
            }
        } finally {
            deleteQuietly(staging);
        }
        return target;
    }

    private static List<Entry> parseManifest(String manifest) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (String line : manifest.split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] f = line.split("\t");
            if (f.length != 3) {
                throw new IOException("bad line in the natives MANIFEST: " + line);
            }
            String path = f[0];
            if (path.startsWith("/") || path.contains("..") || path.contains("\\")) {
                throw new IOException("unsafe path in the natives MANIFEST: " + path);
            }
            try {
                entries.add(new Entry(path, Long.parseLong(f[1]), f[2]));
            } catch (NumberFormatException ex) {
                throw new IOException("bad size in the natives MANIFEST: " + line);
            }
        }
        if (entries.isEmpty()) {
            throw new IOException("the natives MANIFEST lists no files");
        }
        return entries;
    }

    private static void copyVerified(ResourceSource source, Entry e, Path dir) throws IOException {
        Path out = dir.resolve(e.path);
        Files.createDirectories(out.getParent());
        MessageDigest md = digest();
        long total = 0;
        try (InputStream in = source.open(e.path); java.io.OutputStream os = Files.newOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
                os.write(buf, 0, n);
                total += n;
            }
        }
        if (total != e.size || !hex(md.digest()).equals(e.sha256)) {
            throw new IOException("the natives bundle is corrupt: " + e.path + " does not match its checksum");
        }
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString(
                e.path.startsWith("lib/") || e.path.startsWith("plugins/") ? "rwxr-xr-x" : "rw-r--r--");
            Files.setPosixFilePermissions(out, perms);
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system
        }
    }

    /**
     * The cache root if it is safe to load native code from: created with private permissions if new,
     * owned by the current user, and not writable by anyone else. Null otherwise.
     */
    private static Path safeRoot(Path root) {
        try {
            if (!Files.exists(root)) {
                Files.createDirectories(root);
                try {
                    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
                } catch (UnsupportedOperationException ignored) {
                    // not a POSIX file system
                }
            }
            if (!Files.isDirectory(root)) {
                return null;
            }
            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(root);
                if (perms.contains(PosixFilePermission.GROUP_WRITE) || perms.contains(PosixFilePermission.OTHERS_WRITE)) {
                    return null;
                }
                String owner = Files.getOwner(root).getName();
                if (!owner.equals(System.getProperty("user.name"))) {
                    return null;
                }
            } catch (UnsupportedOperationException ignored) {
                // cannot check on this file system
            }
            return root;
        } catch (IOException e) {
            return null;
        }
    }

    private static String read(ResourceSource source, String path) throws IOException {
        try (InputStream in = source.open(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] data) {
        return hex(digest().digest(data));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes a)
                    throws IOException {
                    Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.deleteIfExists(d);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
