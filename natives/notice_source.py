#!/usr/bin/env python3
"""Render natives/NOTICE-source.txt for a bundle: the source-code section of its NOTICE.

usage: notice_source.py <gradle.properties>      (prints the text)
       from notice_source import render           (used by the bundlers)

The placeholders are the Mapnik version and the git tag of this release, both read from gradle.properties.
"""
import os
import sys


def properties(path):
    out = {}
    with open(path) as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


def render(gradle_properties):
    p = properties(gradle_properties)
    mapnik = p["mapnik.version"]
    tag = "v%s.%s" % (mapnik, p["wrapper.revision"])
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "NOTICE-source.txt")) as fh:
        text = fh.read()
    return text.replace("{{MAPNIK_VERSION}}", mapnik).replace("{{TAG}}", tag)


if __name__ == "__main__":
    sys.stdout.write(render(sys.argv[1]))
