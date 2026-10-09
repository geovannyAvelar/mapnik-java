#!/usr/bin/env python3
"""Collect an input plugin too heavy for the main bundle, and the libraries only it needs, into an add-on bundle.

usage: bundle_extra_manylinux.py <main bundle dir> <out dir> <platform> <mapnik prefix> <vcpkg installed triplet dir>
                                 <plugin file name> <add-on name>

At run time the add-on is unpacked into the same directory as the main bundle, so its files are laid out the same way
(lib/, plugins/input/, licenses/) and name no path of their own. Same floor as the main bundle: nothing may need more
than glibc 2.28 and libstdc++ from GCC 8. Run inside the manylinux container, after bundle_manylinux.py.
"""
import glob
import hashlib
import os
import re
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from notice_source import render as render_source_notice   # noqa: E402

base, out, platform, prefix, vcpkg_dir, plugin, name = sys.argv[1:8]
base, out = os.path.abspath(base), os.path.abspath(out)
root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

HOST_LIBS = re.compile(r"^(linux-vdso\.so|ld-linux-x86-64\.so|ld-linux-aarch64\.so|libc\.so|libm\.so|libdl\.so|"
                       r"libpthread\.so|librt\.so|libresolv\.so|libutil\.so|libstdc\+\+\.so|libgcc_s\.so)")
FLOORS = {"GLIBC": (2, 28), "GLIBCXX": (3, 4, 25), "CXXABI": (1, 3, 11), "GCC": (8, 0, 0)}


def run(*cmd, env=None):
    r = subprocess.run(cmd, capture_output=True, text=True, env=env)
    if r.returncode != 0:
        sys.exit("ERROR: %s failed (%d):\n%s%s" % (" ".join(cmd), r.returncode, r.stdout, r.stderr))
    return r.stdout


def needs(path, environment):
    found = []
    for line in run("ldd", path, env=environment).splitlines():
        m = re.match(r"\s*(\S+)\s+=>\s+(\S+)", line)
        if m:
            found.append((m.group(1), None if m.group(2) == "not" else m.group(2)))
    return found


env = dict(os.environ)
env["LD_LIBRARY_PATH"] = os.pathsep.join([os.path.join(prefix, "lib"), os.path.join(prefix, "lib64"),
                                          os.path.join(vcpkg_dir, "lib")])

shutil.rmtree(out, ignore_errors=True)
for d in ("lib", "plugins/input", "licenses"):
    os.makedirs(os.path.join(out, d))

src_plugin = next(iter(glob.glob(os.path.join(prefix, "lib*", "mapnik", "input", plugin))), None)
if not src_plugin:
    sys.exit("ERROR: %s not found under %s" % (plugin, prefix))
dest_plugin = os.path.join(out, "plugins", "input", plugin)
shutil.copyfile(src_plugin, dest_plugin)
os.chmod(dest_plugin, 0o755)

added = {}
queue = [dest_plugin]
i = 0
while i < len(queue):
    f = queue[i]
    i += 1
    for lib, path in needs(f, env):
        if HOST_LIBS.match(lib) or os.path.exists(os.path.join(base, "lib", lib)) or lib in added:
            continue      # the host's, or already in the main bundle
        if path is None:
            sys.exit("ERROR: %s needs %s, which was not found" % (os.path.basename(f), lib))
        real = os.path.realpath(path)
        dest = os.path.join(out, "lib", lib)
        shutil.copyfile(real, dest)
        os.chmod(dest, 0o755)
        added[lib] = real
        queue.append(dest)

run("patchelf", "--force-rpath", "--set-rpath", "$ORIGIN/../../lib", dest_plugin)
for lib in added:
    run("patchelf", "--force-rpath", "--set-rpath", "$ORIGIN", os.path.join(out, "lib", lib))
for f in [dest_plugin] + [os.path.join(out, "lib", lib) for lib in added]:
    subprocess.run(["strip", "--strip-unneeded", f], capture_output=True)

too_new = []
for f in [dest_plugin] + [os.path.join(out, "lib", lib) for lib in added]:
    for line in run("objdump", "-T", f).splitlines():
        for kind, ver in re.findall(r"\b(GLIBC|GLIBCXX|CXXABI|GCC)_([0-9]+(?:\.[0-9]+)*)\b", line):
            if tuple(int(x) for x in ver.split(".")) > FLOORS[kind] and "UND" in line:
                too_new.append("%s needs %s_%s" % (os.path.basename(f), kind, ver))
if too_new:
    sys.exit("ERROR: the add-on needs a newer glibc or libstdc++ than the floor:\n  " + "\n  ".join(sorted(set(too_new))))

# licences and NOTICE: the vcpkg port behind each added library
info_dir = os.path.join(os.path.dirname(vcpkg_dir), "vcpkg", "info")
owner = {}
for lst in glob.glob(os.path.join(info_dir, "*.list")):
    port, version, _t = os.path.basename(lst)[: -len(".list")].rsplit("_", 2)
    with open(lst) as fh:
        for line in fh:
            line = line.strip()
            if "/lib/" in line and not line.endswith("/"):
                owner[os.path.basename(line)] = (port, version)
ports = {}
for lib, real in added.items():
    for candidate in (lib, os.path.basename(real)):
        if candidate in owner:
            ports[owner[candidate][0]] = owner[candidate][1]
            break
for port in sorted(ports):
    c = os.path.join(vcpkg_dir, "share", port, "copyright")
    if os.path.isfile(c) and not os.path.exists(os.path.join(base, "licenses", port + ".copyright")):
        shutil.copyfile(c, os.path.join(out, "licenses", port + ".copyright"))
release = os.environ.get("VCPKG_RELEASE", "")
with open(os.path.join(out, "NOTICE." + name), "w") as n:
    n.write("mapnik-java native add-on bundle '%s' for %s\n" % (name, platform))
    n.write("Adds the %s input plugin to the main bundle of the same version.\n\n" % plugin)
    n.write("Libraries this add-on brings, with the vcpkg port and version each was built from. The portfile of a port\n")
    n.write("names the upstream source archive and its checksum:\n")
    for port, version in sorted(ports.items()):
        n.write("  %s %s\n" % (port, version))
        if release:
            n.write("    https://github.com/mapnik/vcpkg/tree/%s/ports/%s\n" % (release, port))
    n.write("  The input plugin itself is part of Mapnik; see the main bundle's NOTICE.\n")
    n.write(render_source_notice(os.path.join(root, "gradle.properties")))

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

# merged with the main bundle, nothing may resolve outside the merged tree
import tempfile   # noqa: E402
merged = tempfile.mkdtemp()
shutil.copytree(base, merged, dirs_exist_ok=True)
shutil.copytree(out, merged, dirs_exist_ok=True)
clean = {k: v for k, v in os.environ.items() if k != "LD_LIBRARY_PATH"}
bad = []
for f in [os.path.join(merged, "plugins", "input", plugin)] + glob.glob(os.path.join(merged, "lib", "*")):
    for lib, path in needs(f, clean):
        if HOST_LIBS.match(lib):
            continue
        if path is None or not path.startswith(merged + os.sep):
            bad.append("%s resolves %s to %s, outside the merged bundle" % (os.path.basename(f), lib, path))
shutil.rmtree(merged, ignore_errors=True)
if bad:
    sys.exit("ERROR: the add-on is not self-contained:\n  " + "\n  ".join(bad))
print("add-on %s: %d libraries, %.1f MB" % (name, len(added), sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(out) for f in fs) / 1e6))
