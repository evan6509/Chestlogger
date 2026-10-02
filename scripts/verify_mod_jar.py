#!/usr/bin/env python3
"""Verify that the release contains the installable mod with the selected version."""

import json
import os
from pathlib import Path
import sys
from zipfile import ZipFile

from release_version import parse_version, properties


def verify(directory, version=None):
    props = properties()
    version = version or os.environ.get("CHESTLOGGER_VERSION", props["mod_version"])
    parse_version(version)
    full_version = f"{version}+mc{props['minecraft_version']}"
    filename = f"chestlogger-csv-{full_version}.jar"
    jars = [path for path in Path(directory).glob("*.jar") if not path.name.endswith("-sources.jar")]
    if len(jars) != 1 or jars[0].name != filename:
        raise ValueError(f"Expected one installable JAR named {filename}; found {jars}")
    with ZipFile(jars[0]) as jar:
        if jar.testzip() is not None:
            raise ValueError("JAR failed its ZIP integrity check")
        metadata = json.loads(jar.read("fabric.mod.json"))
        if metadata["id"] != "chestlogger_csv" or metadata["version"] != full_version:
            raise ValueError("JAR mod identity or embedded version does not match this build")
        if metadata["depends"]["minecraft"] != props["minecraft_version"]:
            raise ValueError("JAR Minecraft dependency does not match this build")
        mixins = json.loads(jar.read("chestlogger-csv.mixins.json"))
        classes = ["com/chestlogger/ChestLoggerCsv.class", "LICENSE", "NOTICE"]
        classes += [mixins["package"].replace(".", "/") + "/" + name.replace(".", "/") + ".class"
                    for name in mixins["mixins"]]
        missing = set(classes) - set(jar.namelist())
        if missing:
            raise ValueError(f"Missing release files: {sorted(missing)}")
        if any(name.endswith("Tests.class") or name.startswith("com/chestlogger/ContainerGameTests")
               for name in jar.namelist()):
            raise ValueError("Test classes are included in the installable JAR")
    return jars[0], metadata


def main():
    jar, metadata = verify(sys.argv[1] if len(sys.argv) > 1 else "build/libs")
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"jar={jar.as_posix()}\n")
    print(f"Verified {jar} (mod version {metadata['version']})")


if __name__ == "__main__":
    main()
