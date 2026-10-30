#!/usr/bin/env python3
"""Build isolated failure-injection releases for an already running local Paper validation server.

Uses javac and real production module sources. Fixtures and their JVM counters must never be deployed
to a production server. Host changes require rebuilding these fixtures after restarting the host.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile


def sha(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def build(repo, server, java_home, label):
    status = json.loads((server / "plugins/QuantumPlugin/hot/status.json").read_text())
    if not status["running"] or not status["phase"] == "ready":
        raise RuntimeError("Start the compatible local host before making fixtures")
    output = server / "fixtures" / label
    output.mkdir(parents=True, exist_ok=False)
    host = server / "plugins/QuantumPlugin.jar"
    # The full host contains the shared hot API; no hardcoded artifact version is needed here.
    cp = os.pathsep.join(map(str, [host, *sorted((server / "libraries").rglob("*.jar"))]))
    source = (repo / "modules/sponsor-prefix/src/main/java/vip/qoriginal/modules/sponsors/SponsorPrefixModule.java").read_text()
    releases = {}
    for version in ("v1", "v2", "broken"):
        directory = output / version
        classes = directory / "classes"
        classes.mkdir(parents=True)
        entry = directory / "SponsorPrefixModule.java"
        changed = source.replace("§b[SPONSOR]§f", f"§d[{version.upper()}]§f")
        # Only JDK-owned counter values escape the child loader. No business object is stored in the parent.
        injected = '''
        java.util.concurrent.atomic.AtomicLong ticks = (java.util.concurrent.atomic.AtomicLong) System.getProperties()
            .computeIfAbsent("qhot.validation.ticks.VERSION", key -> new java.util.concurrent.atomic.AtomicLong());
        java.util.concurrent.atomic.AtomicLong events = (java.util.concurrent.atomic.AtomicLong) System.getProperties()
            .computeIfAbsent("qhot.validation.events", key -> new java.util.concurrent.atomic.AtomicLong());
        context.repeatSync(() -> { System.setProperty("qhot.validation.version", "VERSION"); ticks.incrementAndGet(); }, 1, 1);
        context.listen(org.bukkit.event.server.ServerCommandEvent.class, EventPriority.NORMAL, false, event -> {
            if (event.getCommand().equals("__qhot_bench__")) events.incrementAndGet();
        });
'''.replace("VERSION", version)
        changed = changed.replace("// Chambers did not previously install NamePrefix.", injected)
        if version == "broken":
            changed = changed.replace("private void update(String text)", '@Override public void healthCheck() { throw new IllegalStateException("validation health failure"); }\n    private void update(String text)')
        entry.write_text(changed)
        subprocess.run([str(java_home / "bin/javac"), "-encoding", "UTF-8", "-cp", cp, "-d", str(classes), str(entry)], check=True)
        release_id = label + "-" + version
        release = directory / release_id
        release.mkdir()
        module = release / "sponsor-prefix.jar"
        with zipfile.ZipFile(module, "w", zipfile.ZIP_DEFLATED) as jar:
            for file in sorted(classes.rglob("*.class")):
                jar.write(file, file.relative_to(classes))
            jar.writestr("META-INF/qplugin-module.properties", f"id=sponsor-prefix\nversion={release_id}\napiVersion=1\nentrypoint=vip.qoriginal.modules.sponsors.SponsorPrefixModule\n")
        modules = [module]
        # The original baseline had only a prefix module. Later hosts also embed daily-login.
        with zipfile.ZipFile(host) as jar:
            for name in jar.namelist():
                if name.startswith("hot-modules/") and name.endswith(".jar") and not name.endswith("/sponsor-prefix.jar"):
                    file = release / Path(name).name
                    file.write_bytes(jar.read(name))
                    modules.append(file)
        def artifact(file, identifier, version):
            return dict(id=identifier, version=version, file=file.name, sha256=sha(file), size=file.stat().st_size)
        descriptors = []
        for file in modules:
            with zipfile.ZipFile(file) as jar:
                props = dict(line.split("=", 1) for line in jar.read("META-INF/qplugin-module.properties").decode().splitlines() if "=" in line)
            descriptors.append(artifact(file, props["id"], props["version"]))
        shutil.copy2(host, release / "QuantumPlugin.jar")
        manifest = dict(formatVersion=1, apiVersion=1, releaseId=release_id,
                        hosts={"QuantumPlugin": status["hostCodeSha256"]}, modules=descriptors,
                        plugins=[artifact(release / "QuantumPlugin.jar", "QuantumPlugin", release_id)])
        (release / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        archive = output / (release_id + ".zip")
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zipped:
            for file in sorted(release.iterdir()):
                zipped.write(file, file.name)
        releases[version] = str(archive)
    # An empty business module gives a controlled A/B comparison with the same permanent host.
    idle_build = output / "idle"
    idle_build.mkdir()
    idle_source = idle_build / "IdleModule.java"
    idle_source.write_text('package fixture; public final class IdleModule implements vip.qoriginal.quantumplugin.hot.api.HotModule { public void prepare(vip.qoriginal.quantumplugin.hot.api.ModuleContext context) { } }')
    subprocess.run([str(java_home / "bin/javac"), "-cp", cp, "-d", str(idle_build), str(idle_source)], check=True)
    idle_jar = idle_build / "validation-idle.jar"
    idle_id = label + "-idle"
    with zipfile.ZipFile(idle_jar, "w", zipfile.ZIP_DEFLATED) as jar:
        jar.write(idle_build / "fixture/IdleModule.class", "fixture/IdleModule.class")
        jar.writestr("META-INF/qplugin-module.properties", f"id=validation-idle\nversion={idle_id}\napiVersion=1\nentrypoint=fixture.IdleModule\n")
    manifest["releaseId"] = idle_id
    manifest["modules"] = [artifact(idle_jar, "validation-idle", idle_id)]
    idle_archive = output / (idle_id + ".zip")
    with zipfile.ZipFile(idle_archive, "w", zipfile.ZIP_DEFLATED) as zipped:
        zipped.write(idle_jar, idle_jar.name)
        zipped.write(host, "QuantumPlugin.jar")
        zipped.writestr("manifest.json", json.dumps(manifest, indent=2) + "\n")
    releases["idle"] = str(idle_archive)
    (output / "fixtures.json").write_text(json.dumps(releases, indent=2) + "\n")
    return releases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-dir", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--label", required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    print(json.dumps(build(repo, args.server_dir.resolve(), args.java_home, args.label), indent=2))


if __name__ == "__main__":
    main()
