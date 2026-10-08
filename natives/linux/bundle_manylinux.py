#!/usr/bin/env python3
"""Collect the shim, Mapnik and everything they need into one relocatable directory (portable Linux).

usage: bundle_manylinux.py <out dir> <platform> <mapnik prefix> <libmapnik_c.so> <vcpkg installed triplet dir> <dejavu dir>

Run inside the manylinux_2_28 container by in-container.sh. Layout, as in the other platforms: lib/ (every library),
plugins/input/ (Mapnik's input plugins), fonts/, proj/, certs/, licenses/, NOTICE, MANIFEST.

Not bundled: glibc and the C++ runtime. The bundle is built against glibc 2.28 and libstdc++ from GCC 8 (the
system's, plus a few newer pieces GCC Toolset links in statically), and this script refuses to finish if any
file in it needs more than that: it reads the symbol versions of every library. So the host provides them, and
the bundle runs on any Linux that has glibc 2.28 and libstdc++ from GCC 8 or later.
"""
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from notice_source import render as render_source_notice   # noqa: E402

out, platform, prefix, shim, vcpkg_dir, dejavu = sys.argv[1:7]
out = os.path.abspath(out)
root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Provided by the host and never bundled: the C library family and the C++ runtime. A bundled copy would be ignored
# once the JVM has loaded the host's, and mixing versions breaks things.
HOST_LIBS = re.compile(r"^(linux-vdso\.so|ld-linux-x86-64\.so|ld-linux-aarch64\.so|libc\.so|libm\.so|libdl\.so|"
                       r"libpthread\.so|librt\.so|libresolv\.so|libutil\.so|libstdc\+\+\.so|libgcc_s\.so)")
# The newest each kind of symbol version may be: glibc 2.28, libstdc++ of GCC 8 (GLIBCXX_3.4.25, CXXABI_1.3.11).
FLOORS = {"GLIBC": (2, 28), "GLIBCXX": (3, 4, 25), "CXXABI": (1, 3, 11), "GCC": (8, 0, 0)}

DEJAVU_URL = "https://github.com/dejavu-fonts/dejavu-fonts/releases/download/version_2_37/dejavu-fonts-ttf-2.37.tar.bz2"
DEJAVU_SHA256 = "fa9ca4d13871dd122f61258a80d01751d603b4d3ee14095d65453b4e846e17d7"


def run(*cmd, env=None, check=True):
    r = subprocess.run(cmd, capture_output=True, text=True, env=env)
    if check and r.returncode != 0:
        sys.exit("ERROR: %s failed (%d):\n%s%s" % (" ".join(cmd), r.returncode, r.stdout, r.stderr))
    return r.stdout


env = dict(os.environ)
env["LD_LIBRARY_PATH"] = os.pathsep.join([os.path.join(prefix, "lib"), os.path.join(prefix, "lib64"), os.path.join(vcpkg_dir, "lib")])


def needs(path, environment):
    """The libraries an ELF file asks for, as (name, resolved path or None)."""
    found = []
    for line in run("ldd", path, env=environment).splitlines():
        m = re.match(r"\s*(\S+)\s+=>\s+(\S+)", line)
        if m:
            found.append((m.group(1), None if m.group(2) == "not" else m.group(2)))
    return found


shutil.rmtree(out, ignore_errors=True)
for d in ("lib", "plugins/input", "fonts", "proj", "licenses", "certs"):
    os.makedirs(os.path.join(out, d))

bundled = {}      # library name -> real source path
queue = []


def add_lib(name, src):
    if name in bundled:
        return
    bundled[name] = src
    dest = os.path.join(out, "lib", name)
    shutil.copyfile(src, dest)
    os.chmod(dest, 0o755)
    queue.append(dest)


add_lib("libmapnik_c.so", shim)
# Mapnik installs into lib64 on this system
mapnik_so = os.path.realpath(next(iter(glob.glob(os.path.join(prefix, "lib*", "libmapnik.so"))), os.path.join(prefix, "lib", "libmapnik.so")))
soname = re.search(r"Library soname: \[(.*)\]", run("readelf", "-d", mapnik_so)).group(1)
add_lib(soname, mapnik_so)

# Plugins that ship as add-on bundles (see bundle_extra.py) are not in the main one.
EXTRA_PLUGINS = {"postgis+pgraster.input"}
plugins = []
for p in sorted(glob.glob(os.path.join(prefix, "lib*", "mapnik", "input", "*.input"))):
    if os.path.basename(p) in EXTRA_PLUGINS:
        continue
    dest = os.path.join(out, "plugins", "input", os.path.basename(p))
    shutil.copyfile(p, dest)
    os.chmod(dest, 0o755)
    plugins.append(dest)
    queue.append(dest)
if not plugins:
    sys.exit("ERROR: no input plugins under " + prefix)

i = 0
while i < len(queue):
    f = queue[i]
    i += 1
    for name, path in needs(f, env):
        if HOST_LIBS.match(name):
            continue
        if path is None:
            sys.exit("ERROR: %s needs %s, which was not found" % (os.path.basename(f), name))
        if not path.startswith("/"):
            continue
        add_lib(name, os.path.realpath(path))

# Everything finds its neighbours next to it: no reliance on LD_LIBRARY_PATH or system copies.
# ICU's data library is all data and needs nothing; editing or stripping it breaks the alignment of its segments.
DATA_ONLY = re.compile(r"^libicudata\.so")
for f in glob.glob(os.path.join(out, "lib", "*")):
    if not DATA_ONLY.match(os.path.basename(f)):
        run("patchelf", "--force-rpath", "--set-rpath", "$ORIGIN", f)
for f in plugins:
    run("patchelf", "--force-rpath", "--set-rpath", "$ORIGIN/../../lib", f)
for f in glob.glob(os.path.join(out, "lib", "*")) + plugins:
    if not DATA_ONLY.match(os.path.basename(f)):
        subprocess.run(["strip", "--strip-unneeded", f], capture_output=True)

# ---- the floor: nothing here may need a newer glibc or libstdc++ than the old systems have
too_new = []
for f in glob.glob(os.path.join(out, "lib", "*")) + plugins:
    for line in run("objdump", "-T", f).splitlines():
        for kind, ver in re.findall(r"\b(GLIBC|GLIBCXX|CXXABI|GCC)_([0-9]+(?:\.[0-9]+)*)\b", line):
            if tuple(int(x) for x in ver.split(".")) > FLOORS[kind] and "UND" in line:
                too_new.append("%s needs %s_%s" % (os.path.basename(f), kind, ver))
if too_new:
    sys.exit("ERROR: the bundle needs a newer glibc or libstdc++ than the floor (glibc 2.28, GLIBCXX_3.4.25):\n  "
             + "\n  ".join(sorted(set(too_new))))

# ---- data the libraries look for at run time
archive = os.path.join(os.path.dirname(os.path.abspath(dejavu)), "dejavu.tar.bz2")
if not os.path.isfile(archive):
    urllib.request.urlretrieve(DEJAVU_URL, archive)
with open(archive, "rb") as fh:
    if hashlib.sha256(fh.read()).hexdigest() != DEJAVU_SHA256:
        sys.exit("ERROR: the DejaVu archive does not match its checksum")
shutil.rmtree(dejavu, ignore_errors=True)
with tarfile.open(archive) as t:
    t.extractall(dejavu)
fonts_root = os.path.join(dejavu, "dejavu-fonts-ttf-2.37")
for f in glob.glob(os.path.join(fonts_root, "ttf", "*.ttf")):
    shutil.copyfile(f, os.path.join(out, "fonts", os.path.basename(f)))

proj = os.path.join(vcpkg_dir, "share", "proj")
for name in sorted(os.listdir(proj)):
    src = os.path.join(proj, name)
    if os.path.isfile(src) and (name == "proj.db" or os.path.getsize(src) <= 1_000_000):
        shutil.copyfile(src, os.path.join(out, "proj", name))

import certifi   # noqa: E402
shutil.copyfile(certifi.where(), os.path.join(out, "certs", "cacert.pem"))
for patch in glob.glob(os.path.join(root, "natives", "patches", "*.patch")):
    shutil.copyfile(patch, os.path.join(out, "licenses", os.path.basename(patch)))
try:
    from importlib.metadata import distribution
    d = distribution("certifi")
    lic = next((f for f in (d.files or []) if f.name == "LICENSE"), None)
    if lic:
        shutil.copyfile(str(d.locate_file(lic)), os.path.join(out, "licenses", "certifi.LICENSE"))
except Exception:
    pass

# ---- licences and the NOTICE: the vcpkg port behind each library
shutil.copyfile(os.path.join(prefix, "COPYING"), os.path.join(out, "licenses", "mapnik.COPYING"))
shutil.copyfile(os.path.join(fonts_root, "LICENSE"), os.path.join(out, "licenses", "dejavu-fonts.LICENSE"))
info_dir = os.path.join(os.path.dirname(vcpkg_dir), "vcpkg", "info")
owner = {}     # library file name -> (port, version)
for lst in glob.glob(os.path.join(info_dir, "*.list")):
    port, version, _triplet = os.path.basename(lst)[: -len(".list")].rsplit("_", 2)
    with open(lst) as fh:
        for line in fh:
            line = line.strip()
            if "/lib/" in line and not line.endswith("/"):
                owner[os.path.basename(line)] = (port, version)
ports = {}
for name, src in bundled.items():
    for candidate in (name, os.path.basename(src)):
        if candidate in owner:
            ports[owner[candidate][0]] = owner[candidate][1]
            break
for port in sorted(ports):
    c = os.path.join(vcpkg_dir, "share", port, "copyright")
    if os.path.isfile(c):
        shutil.copyfile(c, os.path.join(out, "licenses", port + ".copyright"))
release = os.environ.get("VCPKG_RELEASE", "")
with open(os.path.join(out, "NOTICE"), "w") as n:
    n.write("mapnik-java native bundle for %s\n" % platform)
    n.write("Mapnik %s\n" % os.environ.get("MAPNIK_VERSION", "unknown"))
    n.write("Needs glibc 2.28 or later and the C++ runtime (libstdc++) of GCC 8 or later.\n\n")
    n.write("Libraries in this bundle, with the vcpkg port and version each was built from. The portfile of a port\n")
    n.write("names the upstream source archive and its checksum:\n")
    for port, version in sorted(ports.items()):
        n.write("  %s %s\n" % (port, version))
        if release:
            n.write("    https://github.com/mapnik/vcpkg/tree/%s/ports/%s\n" % (release, port))
    n.write("  dejavu-fonts 2.37\n    https://github.com/dejavu-fonts/dejavu-fonts/releases/tag/version_2_37\n")
    try:
        import certifi as _c
        n.write("\ncerts/cacert.pem: Mozilla's trusted certificate authorities (MPL 2.0), from the Python package certifi %s,\n"
                % _c.__version__)
        n.write("source https://github.com/certifi/python-certifi\n")
    except Exception:
        pass
    n.write(render_source_notice(os.path.join(root, "gradle.properties")))

# ---- a listing with sizes and checksums, which the Java loader uses to extract and verify the bundle
rows = []
for dirpath, _, files in os.walk(out):
    for fn in files:
        p = os.path.join(dirpath, fn)
        rel = os.path.relpath(p, out).replace(os.sep, "/")
        if rel == "MANIFEST":
            continue
        with open(p, "rb") as fh:
            rows.append("%s\t%d\t%s\n" % (rel, os.path.getsize(p), hashlib.sha256(fh.read()).hexdigest()))
with open(os.path.join(out, "MANIFEST"), "w") as m:
    m.writelines(sorted(rows))

# ---- the bundle must stand on its own: with no library path, everything resolves inside it or is a host library
clean = {k: v for k, v in os.environ.items() if k != "LD_LIBRARY_PATH"}
bad = []
for f in glob.glob(os.path.join(out, "lib", "*")) + plugins:
    for name, path in needs(f, clean):
        if HOST_LIBS.match(name):
            continue
        if path is None or not path.startswith(out + os.sep):
            bad.append("%s resolves %s to %s, outside the bundle" % (os.path.basename(f), name, path))
if bad:
    sys.exit("ERROR: the bundle is not self-contained:\n  " + "\n  ".join(bad))
size = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(out) for f in fs) / 1e6
print("bundle: %d libraries, %d plugins, %.0f MB" % (len(bundled), len(plugins), size))
