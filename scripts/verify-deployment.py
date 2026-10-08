#!/usr/bin/env python3
"""Check a Maven Central deployment zip (from ./gradlew nmcpZipAggregation) before anything is uploaded.

usage: verify-deployment.py <zip> <version> [--signed] [--keyring <public keyring>]
                            [--natives a,b,c] [--max-mb N]

Checks, for the library and every natives artifact:
  * all of the jar, sources jar, javadoc jar and pom are there, and are not empty
  * the checksum files (md5, sha1, sha512) match the files
  * with --signed: every file has an .asc signature, and with --keyring, that it verifies
  * the pom has what Central requires (name, description, url, license, developer, scm) and the right version
  * the jar contents are what the artifact claims: the library carries its version and module name; a natives
    jar carries a MANIFEST and its libraries; an add-on carries its plugin
Exit status is 0 if everything holds, 1 otherwise, with every problem listed.
"""
import argparse
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ALL_NATIVES = [
    "linux-x86_64", "linux-aarch64", "macos-aarch64", "macos-x86_64", "windows-x86_64",
    "linux-x86_64-postgis", "linux-aarch64-postgis",
]
GROUP_PATH = "dev/avelar"
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}

problems = []


def problem(message):
    problems.append(message)


def digest(data, name):
    return hashlib.new(name, data).hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("zip")
    ap.add_argument("version")
    ap.add_argument("--signed", action="store_true")
    ap.add_argument("--keyring", help="a GPG public keyring (or key file) to verify signatures against")
    ap.add_argument("--natives", default=",".join(ALL_NATIVES), help="the natives artifacts that must be present")
    ap.add_argument("--max-mb", type=int, default=900, help="fail above this deployment size")
    args = ap.parse_args()

    size_mb = os.path.getsize(args.zip) / 1e6
    print("deployment: %s, %.1f MB" % (args.zip, size_mb))
    if size_mb > args.max_mb:
        problem("the deployment is %.0f MB, over the %d MB this check allows" % (size_mb, args.max_mb))

    z = zipfile.ZipFile(args.zip)
    names = set(z.namelist())
    artifacts = ["mapnik-java"] + ["mapnik-java-natives-" + n for n in args.natives.split(",") if n]

    # nothing unexpected: every artifact directory in the zip is one we list
    found_dirs = set()
    for n in names:
        m = re.match(r"%s/([^/]+)/([^/]+)/" % re.escape(GROUP_PATH), n)
        if m:
            found_dirs.add(m.group(1))
            if m.group(2) != args.version:
                problem("%s has a file under version %s, not %s" % (m.group(1), m.group(2), args.version))
    for extra in sorted(found_dirs - set(artifacts)):
        problem("the deployment contains an artifact that was not expected: %s" % extra)

    keyring_args = []
    if args.signed and args.keyring:
        keyring_args = ["--no-default-keyring", "--keyring", args.keyring]

    for art in artifacts:
        base = "%s/%s/%s/%s-%s" % (GROUP_PATH, art, args.version, art, args.version)
        files = {
            "jar": base + ".jar", "sources": base + "-sources.jar",
            "javadoc": base + "-javadoc.jar", "pom": base + ".pom",
        }
        for label, path in files.items():
            if path not in names:
                problem("%s: %s is missing (%s)" % (art, label, path))
                continue
            data = z.read(path)
            if len(data) < 100:
                problem("%s: %s is nearly empty (%d bytes)" % (art, label, len(data)))
            for algo, suffix in (("md5", ".md5"), ("sha1", ".sha1"), ("sha512", ".sha512")):
                c = path + suffix
                if c not in names:
                    problem("%s: no %s checksum for %s" % (art, algo, path))
                elif z.read(c).decode().split()[0].strip().lower() != digest(data, algo):
                    problem("%s: the %s checksum of %s is wrong" % (art, algo, path))
            if args.signed:
                sig = path + ".asc"
                if sig not in names:
                    problem("%s: %s is not signed" % (art, path))
                elif keyring_args:
                    verify_signature(path, data, z.read(sig), keyring_args, art)
        if files["pom"] in names:
            check_pom(art, z.read(files["pom"]), args.version)
        if files["jar"] in names:
            check_jar(art, z.read(files["jar"]), args.version)

    print("%d artifacts checked" % len(artifacts))
    if problems:
        print("\n%d problem(s):" % len(problems))
        for p in problems:
            print("  - " + p)
        return 1
    print("deployment is fine")
    return 0


def verify_signature(path, data, sig, keyring_args, art):
    with tempfile.TemporaryDirectory() as d:
        f = os.path.join(d, "file")
        s = os.path.join(d, "file.asc")
        open(f, "wb").write(data)
        open(s, "wb").write(sig)
        r = subprocess.run(["gpg", "--batch", "--status-fd", "1"] + keyring_args + ["--verify", s, f],
                           capture_output=True, text=True)
        if "GOODSIG" not in r.stdout or r.returncode != 0:
            problem("%s: the signature of %s does not verify: %s" % (art, path, (r.stderr or r.stdout).strip()[:200]))


def text(root, path):
    e = root.find(path, POM_NS)
    return e.text.strip() if e is not None and e.text else None


def check_pom(art, data, version):
    try:
        root = ET.fromstring(data)
    except ET.ParseError as e:
        problem("%s: the pom is not valid XML: %s" % (art, e))
        return
    if text(root, "m:groupId") != "dev.avelar":
        problem("%s: groupId is %s" % (art, text(root, "m:groupId")))
    if text(root, "m:artifactId") != art:
        problem("%s: artifactId is %s" % (art, text(root, "m:artifactId")))
    if text(root, "m:version") != version:
        problem("%s: pom version is %s, not %s" % (art, text(root, "m:version"), version))
    for field in ("m:name", "m:description", "m:url"):
        if not text(root, field):
            problem("%s: the pom has no %s" % (art, field.split(":")[1]))
    if not text(root, "m:licenses/m:license/m:name") or not text(root, "m:licenses/m:license/m:url"):
        problem("%s: the pom has no license with a name and url" % art)
    if not text(root, "m:developers/m:developer/m:name"):
        problem("%s: the pom has no developer" % art)
    if not text(root, "m:scm/m:url") or not text(root, "m:scm/m:connection"):
        problem("%s: the pom has no scm url and connection" % art)
    if art.endswith("-postgis"):
        dep = text(root, "m:dependencies/m:dependency/m:artifactId")
        want = art[: -len("-postgis")]
        if dep != want:
            problem("%s: should depend on %s, depends on %s" % (art, want, dep))
        elif text(root, "m:dependencies/m:dependency/m:version") != version:
            problem("%s: depends on %s at another version" % (art, want))


def check_jar(art, data, version):
    with tempfile.NamedTemporaryFile(suffix=".jar") as t:
        t.write(data)
        t.flush()
        try:
            j = zipfile.ZipFile(t.name)
        except zipfile.BadZipFile:
            problem("%s: the jar is not a valid zip file" % art)
            return
        bad = j.testzip()
        if bad:
            problem("%s: the jar is damaged at %s" % (art, bad))
            return
        names = set(j.namelist())
        if art == "mapnik-java":
            manifest = j.read("META-INF/MANIFEST.MF").decode() if "META-INF/MANIFEST.MF" in names else ""
            if "Automatic-Module-Name: dev.avelar.mapnik" not in manifest:
                problem("%s: the jar has no Automatic-Module-Name" % art)
            if "dev/avelar/mapnik/Mapnik.class" not in names:
                problem("%s: the jar has no Mapnik class" % art)
            props = j.read("mapnik-java.properties").decode() if "mapnik-java.properties" in names else ""
            want = ".".join(version.split(".")[:3])
            if "mapnik.version=%s" % want not in props.replace(" ", ""):
                problem("%s: mapnik-java.properties does not say Mapnik %s" % (art, want))
            return
        platform = art[len("mapnik-java-natives-"):]
        root = "dev/avelar/mapnik/natives/%s/" % platform
        if root + "MANIFEST" not in names:
            problem("%s: the jar has no %sMANIFEST" % (art, root))
            return
        listed = [l.split("\t")[0] for l in j.read(root + "MANIFEST").decode().splitlines() if l.strip()]
        for rel in listed:
            if root + rel not in names:
                problem("%s: the MANIFEST lists %s but the jar does not hold it" % (art, rel))
        # The LGPL libraries inside need their source offered: every bundle's NOTICE must carry that section.
        notice = root + ("NOTICE.postgis" if platform.endswith("-postgis") else "NOTICE")
        if notice not in names:
            problem("%s: the jar has no %s" % (art, notice.replace(root, "")))
        else:
            text_ = j.read(notice).decode("utf-8", "replace")
            mapnik = ".".join(version.split(".")[:3])
            for need in ("SOURCE CODE, LICENSES AND YOUR RIGHT TO REPLACE THE LIBRARIES", "Written offer",
                         "https://github.com/mapnik/mapnik/releases/tag/v" + mapnik,
                         "https://github.com/geovannyAvelar/mapnik-java/tree/v" + version):
                if need not in text_:
                    problem("%s: %s lacks \"%s\"" % (art, notice.replace(root, ""), need[:60]))
            if "{{" in text_:
                problem("%s: %s has an unfilled placeholder" % (art, notice.replace(root, "")))
        if platform.endswith("-postgis"):
            if root + "plugins/input/postgis+pgraster.input" not in names:
                problem("%s: the add-on has no postgis plugin" % art)
        else:
            shim = {"macos": "lib/libmapnik_c.dylib", "windows": "lib/mapnik_c.dll"}.get(platform.split("-")[0], "lib/libmapnik_c.so")
            for need in (shim, "plugins/input/geojson.input", "proj/proj.db", "NOTICE"):
                if root + need not in names:
                    problem("%s: the jar has no %s" % (art, need))
            if not any(n.startswith(root + "licenses/") for n in names):
                problem("%s: the jar has no licenses" % art)


if __name__ == "__main__":
    sys.exit(main())
