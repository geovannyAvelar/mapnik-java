#!/usr/bin/env bash
# Collect the shim, Mapnik and everything they need into one relocatable directory.
# Run inside the image built by the Dockerfile next to this file.
set -euo pipefail

OUT="${1:?usage: bundle.sh <output dir> <platform>}"
PLATFORM="${2:?usage: bundle.sh <output dir> <platform>}"
MULTIARCH="$(gcc -dumpmachine)"   # x86_64-linux-gnu, aarch64-linux-gnu
PREFIX=/opt/mapnik
SHIM=/src/shim/build/native/libmapnik_c.so

# Provided by the host and never bundled: the C library family and the C++ runtime. A bundled copy
# would be ignored once the JVM has loaded the host's, and mixing versions breaks things.
HOST_LIBS='^(linux-vdso\.so|ld-linux-x86-64\.so|ld-linux-aarch64\.so|libc\.so|libm\.so|libdl\.so|libpthread\.so|librt\.so|libresolv\.so|libutil\.so|libstdc\+\+\.so|libgcc_s\.so)'

rm -rf "$OUT"
mkdir -p "$OUT/lib" "$OUT/plugins/input" "$OUT/fonts" "$OUT/proj" "$OUT/licenses"

export LD_LIBRARY_PATH="$PREFIX/lib"

declare -A done_libs
queue=()

add_file() {           # add_file <real path> <name to store it under in lib/>
  local src="$1" name="$2"
  [[ -n "${done_libs[$name]:-}" ]] && return
  done_libs[$name]=1
  cp -L "$src" "$OUT/lib/$name"
  queue+=("$OUT/lib/$name")
}

scan() {               # scan <elf file>: copy every library it needs that is not a host library
  local f="$1" name path
  while read -r name _ path _; do
    [[ "$name" =~ $HOST_LIBS ]] && continue
    [[ "$path" == "not" ]] && { echo "ERROR: $f needs $name, which was not found" >&2; exit 1; }
    [[ -z "$path" || "$path" != /* ]] && continue
    add_file "$path" "$name"
  done < <(ldd "$f" | awk '/=>/ {print $1, $2, $3, $4}')
}

# Mapnik itself, found by the name the shim asks for.
add_file "$SHIM" libmapnik_c.so
mapnik_so="$(readlink -f "$PREFIX"/lib/libmapnik.so)"
mapnik_soname="$(readelf -d "$mapnik_so" | sed -n 's/.*Library soname: \[\(.*\)\]/\1/p')"
add_file "$mapnik_so" "$mapnik_soname"

# Plugins that ship as add-on bundles (see bundle-extra.sh), not in the main one.
EXTRA_PLUGINS=' postgis+pgraster.input '

for p in "$PREFIX"/lib/mapnik/input/*.input; do
  [[ "$EXTRA_PLUGINS" == *" $(basename "$p") "* ]] && continue
  cp -L "$p" "$OUT/plugins/input/$(basename "$p")"
  scan "$OUT/plugins/input/$(basename "$p")"
done

while ((${#queue[@]})); do
  f="${queue[0]}"; queue=("${queue[@]:1}")
  scan "$f"
done

# Everything finds its neighbours next to it: no reliance on LD_LIBRARY_PATH or system copies.
for f in "$OUT"/lib/*; do
  patchelf --force-rpath --set-rpath '$ORIGIN' "$f"
done
for f in "$OUT"/plugins/input/*.input; do
  patchelf --force-rpath --set-rpath '$ORIGIN/../../lib' "$f"
done
strip --strip-unneeded "$OUT"/lib/* "$OUT"/plugins/input/*.input 2>/dev/null || true

# Data the libraries look for at run time.
cp /usr/share/fonts/truetype/dejavu/*.ttf "$OUT/fonts/"
cp -r /usr/share/proj/. "$OUT/proj/"

# Licences: Mapnik's, and the copyright file of the package behind each bundled library.
cp "$PREFIX"/share/doc/mapnik*/COPYING "$OUT/licenses/mapnik.COPYING" 2>/dev/null \
  || cp /src/mapnik/COPYING "$OUT/licenses/mapnik.COPYING"
packages=()
for f in "$OUT"/lib/* "$OUT"/fonts/*.ttf; do
  src="$(readlink -f "/usr/lib/${MULTIARCH}/$(basename "$f")" 2>/dev/null || true)"
  pkg="$(dpkg -S "$src" 2>/dev/null | head -1 | cut -d: -f1 || true)"
  [[ -n "$pkg" ]] && packages+=("$pkg")
done
packages+=(proj-data fonts-dejavu-core ca-certificates)
# Trusted certificates for HTTPS tile sources, and the change made to Mapnik for them.
mkdir -p "$OUT/certs"
cp /etc/ssl/certs/ca-certificates.crt "$OUT/certs/cacert.pem"
cp /src/patches/*.patch "$OUT/licenses/"
for pkg in $(printf '%s\n' "${packages[@]}" | sort -u); do
  [[ -f "/usr/share/doc/$pkg/copyright" ]] && cp "/usr/share/doc/$pkg/copyright" "$OUT/licenses/$pkg.copyright"
done
{
  echo "mapnik-java native bundle for ${PLATFORM}"
  echo "Mapnik $(grep '^mapnik.version=' /usr/local/share/gradle.properties | cut -d= -f2)"
  echo
  echo "Libraries in this bundle, with the Ubuntu source package and version each was built from"
  echo "(the source of an exact version is on the Launchpad page given):"
  for pkg in $(printf '%s\n' "${packages[@]}" | sort -u); do
    dpkg-query -W -f='  ${Package} ${Version}\n    source: ${source:Package} ${source:Version}\n    https://launchpad.net/ubuntu/+source/${source:Package}/${source:Version}\n' "$pkg" 2>/dev/null || true
  done
  python3 /usr/local/share/notice_source.py /usr/local/share/gradle.properties
} > "$OUT/NOTICE"

# A listing with sizes and checksums, which the Java loader uses to extract and verify the bundle.
( cd "$OUT" && find . -type f ! -name MANIFEST | sed 's|^\./||' | sort | while read -r rel; do
    printf '%s\t%s\t%s\n' "$rel" "$(stat -c %s "$rel")" "$(sha256sum "$rel" | cut -d' ' -f1)"
  done > MANIFEST )

# Check the result stands on its own: with no library path, everything resolves inside the bundle or
# is a host library.
unset LD_LIBRARY_PATH
bad=0
for f in "$OUT"/lib/* "$OUT"/plugins/input/*.input; do
  while read -r name _ path _; do
    [[ "$name" =~ $HOST_LIBS ]] && continue
    if [[ "$path" != "$OUT"/* ]]; then
      echo "ERROR: $(basename "$f") resolves $name to ${path:-nothing}, outside the bundle" >&2
      bad=1
    fi
  done < <(ldd "$f" | awk '/=>/ {print $1, $2, $3, $4}')
done
((bad == 0)) || exit 1

echo "bundle: $(ls "$OUT"/lib | wc -l) libraries, $(ls "$OUT"/plugins/input | wc -l) plugins, $(du -sh "$OUT" | cut -f1)"
