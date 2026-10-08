#!/usr/bin/env bash
# Collect an input plugin that is too heavy for the main bundle, and the libraries only it needs, into
# an add-on bundle. At run time the add-on is unpacked into the same directory as the main bundle, so
# its files are laid out the same way (lib/, plugins/input/, licenses/) and use no path of their own.
# Run inside the image built by the Dockerfile, after bundle.sh.
set -euo pipefail

BASE="${1:?usage: bundle-extra.sh <main bundle dir> <output dir> <platform> <plugin file name> [add-on name]}"
OUT="${2:?}"
PLATFORM="${3:?}"
PLUGIN="${4:?}"
NAME="${5:-${PLUGIN%.input}}"
PREFIX=/opt/mapnik
HOST_LIBS='^(linux-vdso\.so|ld-linux-x86-64\.so|ld-linux-aarch64\.so|libc\.so|libm\.so|libdl\.so|libpthread\.so|librt\.so|libresolv\.so|libutil\.so|libstdc\+\+\.so|libgcc_s\.so)'

rm -rf "$OUT"
mkdir -p "$OUT/lib" "$OUT/plugins/input" "$OUT/licenses"
export LD_LIBRARY_PATH="$PREFIX/lib"

declare -A done_libs
queue=()

scan() {               # scan <elf file>: copy every library it needs that the main bundle does not have
  local f="$1" name path
  while read -r name _ path _; do
    [[ "$name" =~ $HOST_LIBS ]] && continue
    [[ "$path" == "not" ]] && { echo "ERROR: $f needs $name, which was not found" >&2; exit 1; }
    [[ -z "$path" || "$path" != /* ]] && continue
    [[ -e "$BASE/lib/$name" ]] && continue          # already shipped with the main bundle
    [[ -n "${done_libs[$name]:-}" ]] && continue
    done_libs[$name]=1
    cp -L "$path" "$OUT/lib/$name"
    queue+=("$OUT/lib/$name")
  done < <(ldd "$f" | awk '/=>/ {print $1, $2, $3, $4}')
}

cp -L "$PREFIX/lib/mapnik/input/$PLUGIN" "$OUT/plugins/input/$PLUGIN"
scan "$OUT/plugins/input/$PLUGIN"
while ((${#queue[@]})); do
  f="${queue[0]}"; queue=("${queue[@]:1}")
  scan "$f"
done

for f in "$OUT"/lib/*; do
  [[ -e "$f" ]] && patchelf --force-rpath --set-rpath '$ORIGIN' "$f"
done
patchelf --force-rpath --set-rpath '$ORIGIN/../../lib' "$OUT/plugins/input/$PLUGIN"
strip --strip-unneeded "$OUT"/lib/* "$OUT/plugins/input/$PLUGIN" 2>/dev/null || true

# Licences of the packages behind the libraries this add-on brings (the main bundle has its own).
MULTIARCH="$(gcc -dumpmachine)"
packages=()
for f in "$OUT"/lib/*; do
  [[ -e "$f" ]] || continue
  src="$(readlink -f "/usr/lib/${MULTIARCH}/$(basename "$f")" 2>/dev/null || true)"
  pkg="$(dpkg -S "$src" 2>/dev/null | head -1 | cut -d: -f1 || true)"
  [[ -n "$pkg" ]] && packages+=("$pkg")
done
for pkg in $(printf '%s\n' "${packages[@]:-}" | sort -u); do
  [[ -z "$pkg" ]] && continue
  [[ -f "/usr/share/doc/$pkg/copyright" && ! -e "$BASE/licenses/$pkg.copyright" ]] && cp "/usr/share/doc/$pkg/copyright" "$OUT/licenses/$pkg.copyright"
done
{
  echo "mapnik-java native add-on bundle '$NAME' for $PLATFORM"
  echo "Adds the $PLUGIN input plugin to the main bundle of the same version."
  echo
  echo "Libraries this add-on brings, with the Ubuntu source package and version each was built from"
  echo "(the source of an exact version is on the Launchpad page given):"
  for pkg in $(printf '%s\n' "${packages[@]:-}" | sort -u); do
    [[ -z "$pkg" ]] && continue
    dpkg-query -W -f='  ${Package} ${Version}\n    source: ${source:Package} ${source:Version}\n    https://launchpad.net/ubuntu/+source/${source:Package}/${source:Version}\n' "$pkg" 2>/dev/null || true
  done
  echo "  The input plugin itself is part of Mapnik; see the main bundle's NOTICE."
  python3 /usr/local/share/notice_source.py /usr/local/share/gradle.properties
} > "$OUT/NOTICE.$NAME"

( cd "$OUT" && find . -type f ! -name MANIFEST | sed 's|^\./||' | sort | while read -r rel; do
    printf '%s\t%s\t%s\n' "$rel" "$(stat -c %s "$rel")" "$(sha256sum "$rel" | cut -d' ' -f1)"
  done > MANIFEST )

# Check it stands on its own once merged with the main bundle: nothing resolves outside the merged tree.
MERGED="$(mktemp -d)"
cp -r "$BASE"/. "$MERGED"/
cp -r "$OUT"/. "$MERGED"/
unset LD_LIBRARY_PATH
bad=0
for f in "$MERGED/plugins/input/$PLUGIN" "$MERGED"/lib/*; do
  while read -r name _ path _; do
    [[ "$name" =~ $HOST_LIBS ]] && continue
    if [[ "$path" != "$MERGED"/* ]]; then
      echo "ERROR: $(basename "$f") resolves $name to ${path:-nothing}, outside the merged bundle" >&2
      bad=1
    fi
  done < <(ldd "$f" | awk '/=>/ {print $1, $2, $3, $4}')
done
rm -rf "$MERGED"
((bad == 0)) || exit 1

echo "add-on $NAME: $(ls "$OUT"/lib | wc -l) libraries, $(du -sh "$OUT" | cut -f1)"
