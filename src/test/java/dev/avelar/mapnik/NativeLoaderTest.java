package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unpacking a native bundle. Needs no native library. */
class NativeLoaderTest {

    /** A fake bundle held in memory: files by path, with a MANIFEST built from them. */
    private static final class Bundle implements NativeLoader.ResourceSource {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final AtomicInteger opens = new AtomicInteger();
        final List<String> opened = new ArrayList<>();
        Map<String, String> manifestOverride = new LinkedHashMap<>();
        final List<String> extraManifestLines = new ArrayList<>();

        Bundle put(String path, String content) {
            files.put(path, content.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        String manifest() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                if (manifestOverride.containsKey(e.getKey())) {
                    sb.append(manifestOverride.get(e.getKey())).append('\n');
                } else {
                    sb.append(e.getKey()).append('\t').append(e.getValue().length).append('\t').append(sha(e.getValue())).append('\n');
                }
            }
            for (String line : extraManifestLines) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }

        @Override
        public java.io.InputStream open(String path) throws IOException {
            opens.incrementAndGet();
            synchronized (opened) {
                opened.add(path);
            }
            if (path.equals("MANIFEST")) {
                return new ByteArrayInputStream(manifest().getBytes(StandardCharsets.UTF_8));
            }
            byte[] b = files.get(path);
            if (b == null) {
                throw new IOException("no such resource: " + path);
            }
            return new ByteArrayInputStream(b);
        }
    }

    private static String sha(byte[] b) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Bundle sample() {
        return new Bundle().put("lib/libmapnik_c.so", "shim").put("lib/libmapnik.so.4.3", "mapnik")
            .put("plugins/input/geojson.input", "plugin").put("fonts/DejaVuSans.ttf", "font")
            .put("proj/proj.db", "database").put("NOTICE", "notice");
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- platforms

    @Test
    void namesThePlatformBundle() {
        assertEquals("linux-x86_64", NativeLoader.platformOf("Linux", "amd64"));
        assertEquals("linux-x86_64", NativeLoader.platformOf("Linux", "x86_64"));
        assertEquals("linux-aarch64", NativeLoader.platformOf("Linux", "aarch64"));
        assertEquals("macos-aarch64", NativeLoader.platformOf("Mac OS X", "aarch64"));
        assertEquals("macos-x86_64", NativeLoader.platformOf("Mac OS X", "x86_64"));
        assertEquals("windows-x86_64", NativeLoader.platformOf("Windows 11", "amd64"));
    }

    @Test
    void unknownPlatformsHaveNoBundle() {
        assertNull(NativeLoader.platformOf("Linux", "riscv64"));
        assertNull(NativeLoader.platformOf("Linux", "ppc64le"));
        assertNull(NativeLoader.platformOf("Solaris", "amd64"));
        assertNull(NativeLoader.platformOf("", ""));
    }

    // ---------------------------------------------------------------- unpacking

    @Test
    void unpacksEveryFileWithItsContent(@TempDir Path tmp) throws IOException {
        Bundle b = sample();
        Path dir = NativeLoader.extract(b, tmp);
        for (Map.Entry<String, byte[]> e : b.files.entrySet()) {
            assertArrayEquals(e.getValue(), Files.readAllBytes(dir.resolve(e.getKey())), e.getKey());
        }
        assertTrue(Files.exists(dir.resolve(".complete")));
        assertEquals(tmp, dir.getParent());
        assertTrue(dir.getFileName().toString().matches("[0-9a-f]{16}"), dir.getFileName().toString());
    }

    @Test
    void librariesAndPluginsAreExecutable(@TempDir Path tmp) throws IOException {
        Path dir = NativeLoader.extract(sample(), tmp);
        try {
            Set<PosixFilePermission> lib = Files.getPosixFilePermissions(dir.resolve("lib/libmapnik_c.so"));
            assertTrue(lib.contains(PosixFilePermission.OWNER_EXECUTE));
            assertTrue(Files.getPosixFilePermissions(dir.resolve("plugins/input/geojson.input")).contains(PosixFilePermission.OWNER_EXECUTE));
            assertFalse(Files.getPosixFilePermissions(dir.resolve("fonts/DejaVuSans.ttf")).contains(PosixFilePermission.OWNER_EXECUTE));
            assertFalse(lib.contains(PosixFilePermission.GROUP_WRITE));
            assertFalse(lib.contains(PosixFilePermission.OTHERS_WRITE));
        } catch (UnsupportedOperationException notPosix) {
            // nothing to check on this file system
        }
    }

    @Test
    void aSecondCallReusesTheUnpackedDirectory(@TempDir Path tmp) throws IOException {
        Bundle b = sample();
        Path first = NativeLoader.extract(b, tmp);
        b.opened.clear();
        Path second = NativeLoader.extract(b, tmp);
        assertEquals(first, second);
        assertEquals(java.util.Collections.singletonList("MANIFEST"), b.opened, "only the manifest is read the second time");
    }

    @Test
    void nothingIsLeftBehindAfterSuccess(@TempDir Path tmp) throws IOException {
        NativeLoader.extract(sample(), tmp);
        try (Stream<Path> s = Files.list(tmp)) {
            List<String> names = new ArrayList<>();
            s.forEach(p -> names.add(p.getFileName().toString()));
            assertEquals(1, names.size(), names.toString());
            assertFalse(names.get(0).contains(".tmp-"));
        }
    }

    @Test
    void differentBundlesGetDifferentDirectories(@TempDir Path tmp) throws IOException {
        Path a = NativeLoader.extract(sample(), tmp);
        Path b = NativeLoader.extract(sample().put("lib/extra.so", "more"), tmp);
        Path same = NativeLoader.extract(sample(), tmp);
        assertNotEquals(a, b);
        assertEquals(a, same);
    }

    @Test
    void createsTheCacheDirectoryPrivately(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("a").resolve("b").resolve("cache");
        Path dir = NativeLoader.extract(sample(), root);
        assertTrue(dir.startsWith(root));
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(root);
            assertEquals(PosixFilePermissions.fromString("rwx------"), perms);
        } catch (UnsupportedOperationException notPosix) {
            // nothing to check on this file system
        }
    }

    // ---------------------------------------------------------------- integrity

    @Test
    void aCorruptFileIsRefusedAndLeavesNoDirectory(@TempDir Path tmp) throws IOException {
        Bundle b = sample();
        b.manifestOverride.put("lib/libmapnik.so.4.3", "lib/libmapnik.so.4.3\t6\t" + sha("tampered".getBytes(StandardCharsets.UTF_8)));
        IOException e = assertThrows(IOException.class, () -> NativeLoader.extract(b, tmp));
        assertTrue(e.getMessage().contains("corrupt"), e.getMessage());
        assertTrue(e.getMessage().contains("libmapnik.so.4.3"), e.getMessage());
        try (Stream<Path> s = Files.list(tmp)) {
            assertEquals(0, s.count(), "no half-unpacked directory remains");
        }
    }

    @Test
    void aSizeMismatchIsRefused(@TempDir Path tmp) {
        Bundle b = sample();
        b.manifestOverride.put("NOTICE", "NOTICE\t999\t" + sha("notice".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> NativeLoader.extract(b, tmp));
    }

    @Test
    void aFileListedButMissingIsReported(@TempDir Path tmp) throws IOException {
        Bundle b = sample();
        b.extraManifestLines.add("lib/ghost.so\t3\t" + sha("abc".getBytes(StandardCharsets.UTF_8)));
        IOException e = assertThrows(IOException.class, () -> NativeLoader.extract(b, tmp));
        assertTrue(e.getMessage().contains("ghost"), e.getMessage());
        try (Stream<Path> s = Files.list(tmp)) {
            assertEquals(0, s.count(), "no half-unpacked directory remains");
        }
    }

    // ---------------------------------------------------------------- hostile or broken manifests

    private static IOException unpackManifest(Path tmp, final String manifest) {
        return assertThrows(IOException.class, () -> NativeLoader.extract(new NativeLoader.ResourceSource() {
            @Override
            public java.io.InputStream open(String path) throws IOException {
                if (path.equals("MANIFEST")) {
                    return new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8));
                }
                return new ByteArrayInputStream(new byte[] {1});
            }
        }, tmp));
    }

    @Test
    void pathsThatEscapeTheBundleAreRefused(@TempDir Path tmp) throws IOException {
        for (String bad : new String[] {"../evil.so", "lib/../../evil.so", "/etc/passwd", "lib\\evil.so"}) {
            IOException e = unpackManifest(tmp, bad + "\t1\t" + "0".repeat(64) + "\n");
            assertTrue(e.getMessage().contains("unsafe path"), bad + " -> " + e.getMessage());
        }
        try (Stream<Path> s = Files.list(tmp)) {
            assertEquals(0, s.count());
        }
        assertFalse(Files.exists(tmp.resolve("evil.so")));
    }

    @Test
    void brokenManifestsAreRefused(@TempDir Path tmp) {
        assertTrue(unpackManifest(tmp, "").getMessage().contains("no files"));
        assertTrue(unpackManifest(tmp, "just-a-name\n").getMessage().contains("bad line"));
        assertTrue(unpackManifest(tmp, "a\tb\tc\td\n").getMessage().contains("bad line"));
        assertTrue(unpackManifest(tmp, "a\tnot-a-number\t" + "0".repeat(64) + "\n").getMessage().contains("bad size"));
    }

    // ---------------------------------------------------------------- concurrency and unsafe roots

    @Test
    void manyThreadsUnpackingAtOnceAgreeOnOneDirectory(@TempDir Path tmp) throws Exception {
        Bundle b = sample();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Path>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                results.add(pool.submit((Callable<Path>) () -> NativeLoader.extract(b, tmp)));
            }
            Path first = results.get(0).get();
            for (Future<Path> f : results) {
                assertEquals(first, f.get());
            }
            assertEquals("shim", read(first.resolve("lib/libmapnik_c.so")));
            assertTrue(Files.exists(first.resolve(".complete")));
            try (Stream<Path> s = Files.list(tmp)) {
                assertEquals(1, s.count(), "no staging directories left over");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aCacheOthersCanWriteToIsNotUsed(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("shared");
        Files.createDirectory(root);
        try {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (UnsupportedOperationException notPosix) {
            return;
        }
        Path dir = NativeLoader.extract(sample(), root);
        assertFalse(dir.startsWith(root), "code must not be loaded from a directory others can write to: " + dir);
        assertEquals("shim", read(dir.resolve("lib/libmapnik_c.so")));
        try (Stream<Path> s = Files.list(root)) {
            assertEquals(0, s.count(), "and nothing was put there");
        }
    }

    @Test
    void aGroupWritableCacheIsNotUsedEither(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("group");
        Files.createDirectory(root);
        try {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwx---"));
        } catch (UnsupportedOperationException notPosix) {
            return;
        }
        assertFalse(NativeLoader.extract(sample(), root).startsWith(root));
    }

    @Test
    void aCacheRootThatIsAFileIsNotUsed(@TempDir Path tmp) throws IOException {
        Path file = Files.write(tmp.resolve("not-a-dir"), new byte[] {1});
        Path dir = NativeLoader.extract(sample(), file);
        assertFalse(dir.startsWith(file));
        assertEquals("shim", read(dir.resolve("lib/libmapnik_c.so")));
    }

    // ---------------------------------------------------------------- add-on bundles

    private static Bundle postgis() {
        return new Bundle().put("lib/libpq.so.5", "pq").put("plugins/input/postgis.input", "pg").put("NOTICE.postgis", "pg notice");
    }

    @Test
    void anAddOnIsMergedIntoTheSameDirectoryUnderItsOwnId(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path base = NativeLoader.extract(sample(), tmp);
        Path both = NativeLoader.extract(sample(), java.util.Collections.<NativeLoader.ResourceSource>singletonList(postgis()), tmp);
        assertNotEquals(base, both, "each combination of add-ons has its own directory");
        assertEquals("shim", read(both.resolve("lib/libmapnik_c.so")));
        assertEquals("pq", read(both.resolve("lib/libpq.so.5")));
        assertEquals("pg", read(both.resolve("plugins/input/postgis.input")));
        assertEquals("pg notice", read(both.resolve("NOTICE.postgis")));
        assertTrue(Files.exists(both.resolve(".complete")));
        String manifest = read(both.resolve("MANIFEST"));
        assertTrue(manifest.contains("lib/libpq.so.5\t"), manifest);
        assertTrue(manifest.contains("lib/libmapnik_c.so\t"), manifest);
        assertFalse(Files.exists(base.resolve("lib/libpq.so.5")), "the base alone is unchanged");
    }

    @Test
    void anAddOnCannotOverwriteAFileOfTheMainBundle(@org.junit.jupiter.api.io.TempDir Path tmp) {
        Bundle evil = new Bundle().put("lib/libmapnik_c.so", "trojan");
        IOException e = assertThrows(IOException.class,
            () -> NativeLoader.extract(sample(), java.util.Collections.<NativeLoader.ResourceSource>singletonList(evil), tmp));
        assertTrue(e.getMessage().contains("lib/libmapnik_c.so"), e.getMessage());
    }

    @Test
    void aCorruptAddOnFileIsRefused(@org.junit.jupiter.api.io.TempDir Path tmp) {
        Bundle bad = postgis();
        bad.manifestOverride.put("lib/libpq.so.5", "lib/libpq.so.5\t2\t" + sha("tampered".getBytes(StandardCharsets.UTF_8)));
        IOException e = assertThrows(IOException.class,
            () -> NativeLoader.extract(sample(), java.util.Collections.<NativeLoader.ResourceSource>singletonList(bad), tmp));
        assertTrue(e.getMessage().contains("libpq"), e.getMessage());
    }
}
