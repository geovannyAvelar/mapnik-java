#!/usr/bin/env python3
"""Collect the shim, Mapnik and everything they need into one relocatable directory (Windows).

usage: bundle.py <out dir> <platform> <mapnik prefix> <mapnik_c.dll> <vcpkg installed triplet dir> <dejavu dir>

Layout, as in the other platforms: lib/ (every DLL), plugins/input/ (Mapnik's input plugins, which are DLLs
named *.input), fonts/, proj/, licenses/, NOTICE, MANIFEST. Windows finds a DLL's dependencies next to it, which
is why the libraries are together; the loader adds lib/ to the search path for the plugins, which sit elsewhere.

Not bundled: Windows' own DLLs (kernel32, the Universal C Runtime and so on). The Visual C++ runtime
(msvcp140, vcruntime140 ...) is bundled from Visual Studio's redistributable files, so no installer is needed.
Needs the pefile package.
"""
import glob
import hashlib
import os
import re
import shutil
import sys
import tarfile
import urllib.request

import pefile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from notice_source import render as render_source_notice   # noqa: E402

out, platform, prefix, shim, vcpkg_dir, dejavu = sys.argv[1:7]
out = os.path.abspath(out)
system32 = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32")

DEJAVU_URL = "https://github.com/dejavu-fonts/dejavu-fonts/releases/download/version_2_37/dejavu-fonts-ttf-2.37.tar.bz2"
DEJAVU_SHA256 = "fa9ca4d13871dd122f61258a80d01751d603b4d3ee14095d65453b4e846e17d7"

# Redistributable under the Visual Studio licence: bundled so that nothing has to be installed.
VC_RUNTIME = re.compile(r"^(msvcp140.*|vcruntime140.*|concrt140|vcomp140)\.dll$")


def is_system(name):
    n = name.lower()
    if n.startswith("api-ms-win-") or n.startswith("ext-ms-win-"):
        return True
    if VC_RUNTIME.match(n):
        return False
    return os.path.exists(os.path.join(system32, name))


search = [os.path.join(prefix, "bin"), os.path.join(prefix, "lib"), os.path.join(vcpkg_dir, "bin")]
redist = os.environ.get("VCToolsRedistDir", "")
for d in glob.glob(os.path.join(redist, "x64", "Microsoft.VC*.CRT")) if redist else []:
    search.append(d)


def find(name):
    for d in search:
        p = os.path.join(d, name)
        if os.path.isfile(p):
            return p
    if VC_RUNTIME.match(name.lower()):
        p = os.path.join(system32, name)     # the runner has the runtime installed
        if os.path.isfile(p):
            return p
    return None


def imports(path):
    pe = pefile.PE(path, fast_load=True)
    pe.parse_data_directories(directories=[
        pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_IMPORT"],
        pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT"],
    ])
    names = []
    for attr in ("DIRECTORY_ENTRY_IMPORT", "DIRECTORY_ENTRY_DELAY_IMPORT"):
        for entry in getattr(pe, attr, []):
            names.append(entry.dll.decode("ascii"))
    pe.close()
    return names


shutil.rmtree(out, ignore_errors=True)
for d in ("lib", "plugins/input", "fonts", "proj", "licenses"):
    os.makedirs(os.path.join(out, d))

queue = []
bundled = {}       # lower-case DLL name -> path in the bundle


def add_lib(name, src):
    key = name.lower()
    if key in bundled:
        return
    dest = os.path.join(out, "lib", name)
    shutil.copyfile(src, dest)
    bundled[key] = dest
    queue.append(dest)


add_lib("mapnik_c.dll", shim)
# Mapnik's DLL is called libmapnik.dll on Windows, and that is the name the shim imports.
mapnik_dll = next(iter(glob.glob(os.path.join(prefix, "**", "libmapnik.dll"), recursive=True)), None) \
    or next(iter(glob.glob(os.path.join(prefix, "**", "mapnik.dll"), recursive=True)), None)
if not mapnik_dll:
    sys.exit("ERROR: libmapnik.dll not found under " + prefix)
add_lib(os.path.basename(mapnik_dll), mapnik_dll)

plugins = []
# Mapnik installs its plugins under bin/ on Windows, next to the DLLs it builds.
for p in sorted(glob.glob(os.path.join(prefix, "bin", "mapnik", "input", "*.input"))
                + glob.glob(os.path.join(prefix, "lib", "mapnik", "input", "*.input"))):
    dest = os.path.join(out, "plugins", "input", os.path.basename(p))
    shutil.copyfile(p, dest)
    plugins.append(dest)
    queue.append(dest)
if not plugins:
    sys.exit("ERROR: no input plugins under " + prefix)

i = 0
while i < len(queue):
    f = queue[i]
    i += 1
    for dll in imports(f):
        if dll.lower() in bundled or is_system(dll):
            continue
        src = find(dll)
        if not src:
            sys.exit("ERROR: %s needs %s, which was not found (searched %s)" % (os.path.basename(f), dll, search))
        add_lib(dll, src)

# Fonts, pinned by checksum.
archive = os.path.join(os.path.dirname(os.path.abspath(dejavu)), "dejavu.tar.bz2")
if not os.path.isfile(archive):
    os.makedirs(os.path.dirname(archive), exist_ok=True)
    urllib.request.urlretrieve(DEJAVU_URL, archive)
with open(archive, "rb") as fh:
    if hashlib.sha256(fh.read()).hexdigest() != DEJAVU_SHA256:
        sys.exit("ERROR: the DejaVu archive does not match its checksum")
shutil.rmtree(dejavu, ignore_errors=True)
with tarfile.open(archive) as t:
    t.extractall(dejavu, filter="data") if sys.version_info >= (3, 12) else t.extractall(dejavu)
root = os.path.join(dejavu, "dejavu-fonts-ttf-2.37")
for f in glob.glob(os.path.join(root, "ttf", "*.ttf")):
    shutil.copyfile(f, os.path.join(out, "fonts", os.path.basename(f)))

# PROJ's data: the database and the small files, as in the other bundles (grids are hundreds of megabytes).
proj = os.path.join(vcpkg_dir, "share", "proj")
for name in sorted(os.listdir(proj)):
    src = os.path.join(proj, name)
    if os.path.isfile(src) and (name == "proj.db" or os.path.getsize(src) <= 1_000_000):
        shutil.copyfile(src, os.path.join(out, "proj", name))

# Trusted certificates for HTTPS tile sources (Mozilla's list, from the certifi package), and the change made to
# Mapnik for them.
import certifi   # noqa: E402
os.makedirs(os.path.join(out, "certs"))
shutil.copyfile(certifi.where(), os.path.join(out, "certs", "cacert.pem"))
for patch in glob.glob(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "patches", "*.patch")):
    shutil.copyfile(patch, os.path.join(out, "licenses", os.path.basename(patch)))
try:
    from importlib.metadata import distribution
    d = distribution("certifi")
    lic = next((f for f in (d.files or []) if f.name == "LICENSE"), None)
    if lic:
        shutil.copyfile(str(d.locate_file(lic)), os.path.join(out, "licenses", "certifi.LICENSE"))
except Exception:
    pass

# Licences: Mapnik's, DejaVu's, and the copyright file of the vcpkg port behind each bundled DLL.
shutil.copyfile(os.path.join(prefix, "COPYING"), os.path.join(out, "licenses", "mapnik.COPYING"))
shutil.copyfile(os.path.join(root, "LICENSE"), os.path.join(out, "licenses", "dejavu-fonts.LICENSE"))
info_dir = os.path.join(os.path.dirname(vcpkg_dir), "vcpkg", "info")
owner = {}      # DLL file name (lower case) -> (port, version)
for lst in glob.glob(os.path.join(info_dir, "*.list")):
    port, version, _triplet = os.path.basename(lst)[: -len(".list")].rsplit("_", 2)
    with open(lst) as fh:
        for line in fh:
            line = line.strip()
            if line.lower().endswith(".dll"):
                owner[os.path.basename(line).lower()] = (port, version)
ports = {}
for key in bundled:
    if key in owner:
        ports[owner[key][0]] = owner[key][1]
for port, version in sorted(ports.items()):
    c = os.path.join(vcpkg_dir, "share", port, "copyright")
    if os.path.isfile(c):
        shutil.copyfile(c, os.path.join(out, "licenses", port + ".copyright"))
runtime = sorted(n for n in bundled if VC_RUNTIME.match(n))

with open(os.path.join(out, "NOTICE"), "w") as n:
    n.write("mapnik-java native bundle for %s\n" % platform)
    n.write("Mapnik %s\n" % os.environ.get("MAPNIK_VERSION", "unknown"))
    n.write("Needs 64-bit Windows 10 or later.\n\n")
    release = os.environ.get("VCPKG_RELEASE", "")
    n.write("Libraries in this bundle, with the vcpkg port and version each was built from. The portfile of a port\n")
    n.write("names the upstream source archive and its checksum:\n")
    for port, version in sorted(ports.items()):
        n.write("  %s %s\n" % (port, version))
        if release:
            n.write("    https://github.com/mapnik/vcpkg/tree/%s/ports/%s\n" % (release, port))
    n.write("\ncerts/cacert.pem: Mozilla's trusted certificate authorities (MPL 2.0), from the Python package certifi %s,\n" % certifi.__version__)
    n.write("source https://github.com/certifi/python-certifi\n")
    n.write("\nMicrosoft Visual C++ runtime, redistributed under the Visual Studio licence terms:\n")
    for r in runtime:
        n.write("  %s\n" % r)
    n.write(render_source_notice(os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "gradle.properties")))

# A listing with sizes and checksums, which the Java loader uses to extract and verify the bundle.
rows = []
for dirpath, _, files in os.walk(out):
    for fn in files:
        p = os.path.join(dirpath, fn)
        rel = os.path.relpath(p, out).replace(os.sep, "/")
        if rel == "MANIFEST":
            continue
        with open(p, "rb") as fh:
            rows.append("%s\t%d\t%s\n" % (rel, os.path.getsize(p), hashlib.sha256(fh.read()).hexdigest()))
with open(os.path.join(out, "MANIFEST"), "w", newline="\n") as m:
    m.writelines(sorted(rows))

# The bundle must stand on its own: every import is a Windows DLL or something in lib/.
bad = []
for f in list(bundled.values()) + plugins:
    for dll in imports(f):
        if not is_system(dll) and dll.lower() not in bundled:
            bad.append("%s imports %s" % (os.path.basename(f), dll))
if bad:
    sys.exit("ERROR: the bundle is not self-contained:\n  " + "\n  ".join(bad))
print("bundle: %d libraries, %d plugins, %.0f MB" % (len(bundled), len(plugins),
      sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(out) for f in fs) / 1e6))
