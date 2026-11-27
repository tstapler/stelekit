#!/usr/bin/env python3
"""Spike S1: replay Gradle's wasmJs compile, link and wasm-opt outside Gradle and compare bytes.

Prerequisite, from the repo root:
  ./gradlew :kmp:compileKotlinWasmJs --rerun :kmp:compileProductionExecutableKotlinWasmJs --rerun \
    :kmp:compileProductionExecutableKotlinWasmJsOptimize --rerun \
    -PenableJs=true --no-daemon --no-build-cache --max-workers=1 --debug > gradle-debug.log
Then: replay_gradle_wasm.py gradle-debug.log <kotlin_home> <java> <work_dir>
  kotlin_home: the @com_github_jetbrains_kotlin_git repo under `bazel info output_base`/external
  java: a JDK 21 launcher
Also writes <work_dir>/wasmjs-klib-closure.tsv. Libraries and generated sources still come from Gradle.
"""
import filecmp, hashlib, pathlib, subprocess, sys, time

log, kotlin_home, java, work = sys.argv[1:]
work = pathlib.Path(work).resolve()
work.mkdir(parents=True, exist_ok=True)
lines = pathlib.Path(log).read_text(errors="replace").split("\n")
gradle_out = pathlib.Path("kmp/build/compileSync/wasmJs/main/productionExecutable").resolve()


def after(marker):
    return next(l.split(marker, 1)[1] for l in lines if marker in l)


def rewrite(args, out_dir):
    plugins = ",".join(f"{kotlin_home}/lib/{p}.jar" for p in
                       ("compose-compiler-plugin", "kotlinx-serialization-compiler-plugin"))
    result, i = [], 0
    while i < len(args):
        arg = args[i]
        if arg == "-ir-output-dir":
            result += [arg, str(out_dir)]
            i += 2
            continue
        if arg.startswith("-Xplugin="):
            arg = "-Xplugin=" + plugins
        elif arg.startswith("-Xinclude="):
            arg = f"-Xinclude={work}/klib"
        if arg:
            result.append(arg)
        i += 1
    return result


def run(name, command):
    start = time.time()
    with open(work / f"{name}.log", "w") as out:
        subprocess.run(command, check=True, stdout=out, stderr=subprocess.STDOUT, cwd=work)
    print(f"{name}: ok in {time.time() - start:.0f}s")


def kotlinc(name, task):
    args = after(f"{task} Kotlin compiler args: ").split(" ")
    argfile = work / f"{name}.argfile"
    argfile.write_text("\n".join(rewrite(args, work / name)))
    run(name, [java, "-Xmx6g", "-Xss8m", "-cp", f"{kotlin_home}/lib/kotlin-compiler.jar",
               "org.jetbrains.kotlin.cli.js.K2JSCompiler", f"@{argfile}"])
    return args


klib_args = kotlinc("klib", ":kmp:compileKotlinWasmJs")
kotlinc("link", ":kmp:compileProductionExecutableKotlinWasmJs")

opt = next(
    l.split("Command: ", 1)[1] for l in lines if "Starting process" in l and "wasm-opt" in l).split()
flags = [a for a in opt[1:opt.index("-o")] if a.startswith("-")]
run("wasm-opt", [opt[0], *flags, str(work / "link/stelekit-kmp.wasm"), "-o", str(work / "optimized.wasm")])

libraries = klib_args[klib_args.index("-libraries") + 1].split(":")
rows = []
for path in map(pathlib.Path, libraries):
    group, artifact, version = path.parts[-5:-2]
    rows.append(f"{group}:{artifact}:{version}\t{path.name}\t{hashlib.sha256(path.read_bytes()).hexdigest()}")
(work / "wasmjs-klib-closure.tsv").write_text("\n".join(sorted(rows)) + "\n")
print(f"libraries: {len(rows)}")

pairs = [(gradle_out / "kotlin" / n, work / "link" / n) for n in
         ("stelekit-kmp.wasm", "stelekit-kmp.mjs", "stelekit-kmp.import-object.mjs", "stelekit-kmp.js-builtins.mjs")]
pairs.append((gradle_out / "optimized/stelekit-kmp.wasm", work / "optimized.wasm"))
different = 0
for gradle, ours in pairs:
    same = filecmp.cmp(gradle, ours, shallow=False)
    different += not same
    print(f"{'identical' if same else 'DIFFERENT'}  {ours.relative_to(work)}  {ours.stat().st_size} bytes")
sys.exit(1 if different else 0)
