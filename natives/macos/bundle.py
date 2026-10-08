#!/usr/bin/env python3
"""Collect the shim, Mapnik and everything they need into one relocatable directory (macOS).

usage: bundle.py <out dir> <platform> <mapnik prefix> <libmapnik_c.dylib> <dejavu dir> <proj data dir>

Every library is copied next to the others, its install name and its references to the others are
rewritten to @loader_path, and it is signed ad hoc again (Apple Silicon refuses to load code whose
signature no longer matches). Libraries in /usr/lib and /System belong to the system and stay.
"""
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from notice_source import render as render_source_notice   # noqa: E402

out, platform, prefix, shim, dejavu, proj_data = sys.argv[1:7]
out = os.path.abspath(out)


def run(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("ERROR: %s failed (%d):\n%s%s" % (" ".join(cmd), r.returncode, r.stdout, r.stderr))
    return r.stdout


def is_system(ref):
    return ref.startswith("/usr/lib/") or ref.startswith("/System/")


def install_id(path):
    lines = run("otool", "-D", path).splitlines()
    return lines[1].strip() if len(lines) > 1 else None


def dependencies(path):
    own = install_id(path)
    deps = []
    for line in run("otool", "-L", path).splitlines()[1:]:
        ref = line.strip().split(" (")[0]
        if ref and ref != own:
            deps.append(ref)
    return deps


def rpaths(path):
    found, lines = [], run("otool", "-l", path).splitlines()
    for i, line in enumerate(lines):
        if line.strip() == "cmd LC_RPATH":
            m = re.match(r"\s*path (.*) \(offset", lines[i + 2])
            if m:
                found.append(m.group(1))
    return found


# Where @rpath references can be found when a file's own rpaths do not say.
brew = run("brew", "--prefix").strip()
SEARCH = [os.path.join(prefix, "lib"), os.path.join(brew, "lib")]
opt = os.path.join(brew, "opt")
if os.path.isdir(opt):
    SEARCH += [os.path.join(opt, d, "lib") for d in sorted(os.listdir(opt))]


def resolve(ref, origin):
    """The real file a reference inside the file `origin` stands for."""
    here = os.path.dirname(origin)
    if ref.startswith("/"):
        candidates = [ref]
    elif ref.startswith("@loader_path/"):
        candidates = [os.path.join(here, ref[len("@loader_path/"):])]
    elif ref.startswith("@rpath/"):
        name = ref[len("@rpath/"):]
        candidates = []
        for rp in rpaths(origin):
            rp = rp.replace("@loader_path", here)
            candidates.append(os.path.join(rp, name))
        candidates += [os.path.join(d, name) for d in SEARCH]
    else:
        candidates = [os.path.join(d, ref) for d in SEARCH]
    for c in candidates:
        if os.path.exists(c):
            return os.path.realpath(c)
    sys.exit("ERROR: %s needs %s, which was not found" % (origin, ref))


shutil.rmtree(out, ignore_errors=True)
for d in ("lib", "plugins/input", "fonts", "proj", "licenses"):
    os.makedirs(os.path.join(out, d))

by_real = {}        # real source path -> file name in lib/
originals = {}      # file in the bundle -> where it came from (for resolving its references)
queue = []


def add_lib(real, name=None):
    if real in by_real:
        return by_real[real]
    name = name or os.path.basename(real)
    by_real[real] = name
    dest = os.path.join(out, "lib", name)
    shutil.copyfile(real, dest)
    os.chmod(dest, 0o755)
    originals[dest] = real
    queue.append(dest)
    return name


add_lib(os.path.realpath(shim), "libmapnik_c.dylib")

plugin_files = []
for f in sorted(os.listdir(os.path.join(prefix, "lib/mapnik/input"))):
    if f.endswith(".input"):
        dest = os.path.join(out, "plugins/input", f)
        shutil.copyfile(os.path.join(prefix, "lib/mapnik/input", f), dest)
        os.chmod(dest, 0o755)
        originals[dest] = os.path.realpath(os.path.join(prefix, "lib/mapnik/input", f))
        plugin_files.append(dest)
        queue.append(dest)

# The shim asks for Mapnik by rpath; the shim's original rpaths are those of the build tree.
rewrites = {}       # bundle file -> {old reference: bundled name}
i = 0
while i < len(queue):
    f = queue[i]
    i += 1
    rewrites[f] = {}
    for ref in dependencies(f):
        if is_system(ref):
            continue
        real = resolve(ref, originals[f])
        rewrites[f][ref] = add_lib(real)

# Point everything at its neighbours.
for f, refs in rewrites.items():
    is_plugin = f.startswith(os.path.join(out, "plugins"))
    base = "@loader_path/../../lib/" if is_plugin else "@loader_path/"
    args = ["install_name_tool"]
    if not is_plugin:
        args += ["-id", "@loader_path/" + os.path.basename(f)]
    for old, name in refs.items():
        args += ["-change", old, base + name]
    for rp in rpaths(f):
        args += ["-delete_rpath", rp]
    if len(args) > 1:
        run(*args, f)
    subprocess.run(["strip", "-x", f], capture_output=True)   # best effort
    run("codesign", "--force", "--sign", "-", f)

# Data the libraries look for at run time.
for f in os.listdir(os.path.join(dejavu, "ttf")):
    if f.endswith(".ttf"):
        shutil.copyfile(os.path.join(dejavu, "ttf", f), os.path.join(out, "fonts", f))
# PROJ's data from Homebrew carries datum-shift grids of hundreds of megabytes. Keep the database and the
# small files, as the Debian proj-data package the Linux bundle uses does.
for name in sorted(os.listdir(proj_data)):
    src = os.path.join(proj_data, name)
    if os.path.isfile(src) and (name == "proj.db" or os.path.getsize(src) <= 1_000_000):
        shutil.copyfile(src, os.path.join(out, "proj", name))

# Trusted certificates for HTTPS tile sources (Mozilla's list, from Homebrew's ca-certificates), and the change
# made to Mapnik for them.
os.makedirs(os.path.join(out, "certs"))
ca_prefix = run("brew", "--prefix", "ca-certificates").strip()
shutil.copyfile(os.path.join(ca_prefix, "share", "ca-certificates", "cacert.pem"), os.path.join(out, "certs", "cacert.pem"))
ca_real = os.path.realpath(os.path.join(ca_prefix, "share", "ca-certificates", "cacert.pem"))
for patch in glob.glob(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "patches", "*.patch")):
    shutil.copyfile(patch, os.path.join(out, "licenses", os.path.basename(patch)))

# Licences: Mapnik's, DejaVu's, and the notices of the Homebrew formula behind each library.
shutil.copyfile(os.path.join(prefix, "COPYING"), os.path.join(out, "licenses/mapnik.COPYING"))
shutil.copyfile(os.path.join(dejavu, "LICENSE"), os.path.join(out, "licenses/dejavu-fonts.LICENSE"))
formulae = {}
for real in list(by_real) + [os.path.realpath(proj_data), ca_real]:
    m = re.search(r"/Cellar/([^/]+)/([^/]+)/", real)
    if m:
        formulae[m.group(1)] = (m.group(2), real[:real.index(m.group(0)) + len(m.group(0))])
formulae.setdefault("dejavu-fonts", ("2.37", None))
pattern = re.compile(r"^(COPYING|LICEN[CS]E|COPYRIGHT|NOTICE|AUTHORS)", re.I)
for name, (version, cellar) in sorted(formulae.items()):
    if not cellar:
        continue
    for entry in sorted(os.listdir(cellar)):
        p = os.path.join(cellar, entry)
        if pattern.match(entry) and os.path.isfile(p):
            shutil.copyfile(p, os.path.join(out, "licenses", "%s.%s" % (name, entry)))

mapnik_lib = next(n for n in by_real.values() if n.startswith("libmapnik.") and "_c" not in n)
minos = ""
for line in run("otool", "-l", os.path.join(out, "lib/libmapnik_c.dylib")).splitlines():
    if line.strip().startswith("minos"):
        minos = line.split()[1]
        break
with open(os.path.join(out, "NOTICE"), "w") as n:
    n.write("mapnik-java native bundle for %s\n" % platform)
    n.write("Mapnik %s\n" % re.sub(r"^libmapnik\.|\.dylib$", "", mapnik_lib))
    n.write("Needs macOS %s or later.\n\n" % minos)
    n.write("Libraries in this bundle, with the Homebrew formula and version each was built from\n")
    n.write("(the URL is the source archive of that formula):\n")
    try:
        info = json.loads(run("brew", "info", "--json=v2", *[f for f in sorted(formulae) if f != "dejavu-fonts"]))
        sources = {f["name"]: f.get("urls", {}).get("stable", {}).get("url", "") for f in info["formulae"]}
    except Exception:
        sources = {}
    for name, (version, _) in sorted(formulae.items()):
        n.write("  %s %s\n" % (name, version))
        if name == "dejavu-fonts":
            n.write("    https://github.com/dejavu-fonts/dejavu-fonts/releases/tag/version_2_37\n")
        elif sources.get(name):
            n.write("    %s\n" % sources[name])
    n.write(render_source_notice(os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "gradle.properties")))

# A listing with sizes and checksums, which the Java loader uses to extract and verify the bundle.
rows = []
for root, _, files in os.walk(out):
    for fn in files:
        p = os.path.join(root, fn)
        rel = os.path.relpath(p, out)
        if rel == "MANIFEST":
            continue
        with open(p, "rb") as fh:
            rows.append("%s\t%d\t%s\n" % (rel, os.path.getsize(p), hashlib.sha256(fh.read()).hexdigest()))
with open(os.path.join(out, "MANIFEST"), "w") as m:
    m.writelines(sorted(rows))

# Check the result stands on its own: every reference is a system library or a neighbour in the bundle.
bad = []
for f in list(rewrites):
    is_plugin = f.startswith(os.path.join(out, "plugins"))
    for ref in dependencies(f):
        if is_system(ref):
            continue
        if not ref.startswith("@loader_path/"):
            bad.append("%s -> %s" % (os.path.basename(f), ref))
            continue
        target = os.path.normpath(os.path.join(os.path.dirname(f), ref[len("@loader_path/"):]))
        if not os.path.exists(target):
            bad.append("%s -> %s (missing)" % (os.path.basename(f), ref))
    if rpaths(f):
        bad.append("%s still has rpaths %s" % (os.path.basename(f), rpaths(f)))
    run("codesign", "--verify", f)
if bad:
    sys.exit("ERROR: the bundle is not self-contained:\n  " + "\n  ".join(bad))

print("bundle: %d libraries, %d plugins, %s" % (len(by_real), len(plugin_files),
      run("du", "-sh", out).split()[0]))
