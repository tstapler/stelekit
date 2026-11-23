// One-off golden generator (Task 0.1.5b). Run: bun install && bun generate.mjs [outDir]
// Output goes to kmp/src/commonTest/resources/excalidraw/ by default. Not run in CI.
import { getStroke } from "perfect-freehand";
import rough from "roughjs/bundled/rough.esm.js";
import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(process.argv[2] ?? resolve(here, "../../kmp/src/commonTest/resources/excalidraw"));
mkdirSync(outDir, { recursive: true });

// 30 deterministic points (no RNG): a sine stroke with a smooth pressure ramp.
const points = Array.from({ length: 30 }, (_, i) => {
  const x = i * 10;
  const y = Math.round(40 * Math.sin(i / 4) * 1000) / 1000;
  const pressure = Math.round((0.2 + 0.6 * (0.5 - 0.5 * Math.cos((i / 29) * Math.PI))) * 1000) / 1000;
  return { x, y, pressure };
});

const easeOutSine = (t) => Math.sin((t * Math.PI) / 2);
const cases = [
  { name: "defaults", options: { size: 16, thinning: 0.5, smoothing: 0.5, streamline: 0.5, simulatePressure: false, last: true }, easing: "linear" },
  { name: "excalidraw", options: { size: 8.5, thinning: 0.6, smoothing: 0.5, streamline: 0.5, simulatePressure: false, last: true }, easing: "easeOutSine" },
].map((c) => ({
  name: c.name,
  options: c.options,
  easing: c.easing,
  outline: getStroke(points, { ...c.options, easing: c.easing === "easeOutSine" ? easeOutSine : (t) => t }),
}));

writeFileSync(
  resolve(outDir, "freehand-golden.json"),
  JSON.stringify({ generator: "perfect-freehand@1.2.3", input: points, cases }, null, 2) + "\n",
);

const gen = rough.generator();
const seed = 1337;
const shapes = {
  rectangle: gen.rectangle(0, 0, 100, 60, { roughness: 1, seed }),
  ellipse: gen.ellipse(50, 30, 100, 60, { roughness: 1, seed }),
  line: gen.line(0, 0, 100, 60, { roughness: 1, seed }),
};
const out = {
  generator: "roughjs@4.6.6",
  seed,
  shapes: Object.fromEntries(
    Object.entries(shapes).map(([k, d]) => [k, { options: { roughness: 1, seed }, sets: d.sets.map((s) => ({ type: s.type, ops: s.ops })) }]),
  ),
};
writeFileSync(resolve(outDir, "rough-golden.json"), JSON.stringify(out, null, 2) + "\n");
console.log("wrote goldens to", outDir);
