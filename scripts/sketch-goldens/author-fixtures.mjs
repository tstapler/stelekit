// One-off: writes the hand-authored Excalidraw fixtures (Task 0.1.5a) from the v0.18.1 types.ts field list.
import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const outDir = resolve(dirname(fileURLToPath(import.meta.url)), "../../kmp/src/commonTest/resources/excalidraw");
mkdirSync(outDir, { recursive: true });

let n = 0;
const base = (id, type, x, y, w, h, extra = {}) => ({
  id, type, x, y, width: w, height: h, angle: 0,
  strokeColor: "#1e1e1e", backgroundColor: "transparent", fillStyle: "solid", strokeWidth: 2,
  strokeStyle: "solid", roughness: 1, opacity: 100, groupIds: [], frameId: null,
  index: "a" + String.fromCharCode(48 + (n++ % 10)), roundness: null, seed: 1000 + n, version: 1,
  versionNonce: 2000 + n, isDeleted: false, boundElements: null, updated: 1700000000000, link: null, locked: false,
  ...extra,
});
const text = (id, t, x, y, extra = {}) => base(id, "text", x, y, 8 * t.length, 25, {
  text: t, originalText: t, fontSize: 20, fontFamily: 5, textAlign: "left", verticalAlign: "top",
  containerId: null, autoResize: true, lineHeight: 1.25, ...extra,
});
const linear = (id, type, x, y, pts, extra = {}) => base(id, type, x, y,
  Math.max(...pts.map((p) => p[0])), Math.max(...pts.map((p) => Math.abs(p[1]))), {
  points: pts, lastCommittedPoint: null, startBinding: null, endBinding: null,
  startArrowhead: null, endArrowhead: type === "arrow" ? "arrow" : null, ...(type === "arrow" ? { elbowed: false } : {}), ...extra,
});
const scene = (elements, files = {}) => ({
  type: "excalidraw", version: 2, source: "https://excalidraw.com (hand-authored from types.ts v0.18.1)",
  elements, appState: { gridSize: null, viewBackgroundColor: "#ffffff" }, files,
});
const write = (name, s) => writeFileSync(resolve(outDir, name), JSON.stringify(s, null, 2) + "\n");

write("shapes-text.excalidraw", scene([
  base("rect1", "rectangle", 0, 0, 160, 80, { roundness: { type: 3 }, boundElements: [{ id: "t1", type: "text" }], backgroundColor: "#a5d8ff" }),
  text("t1", "Hello", 50, 28, { containerId: "rect1", textAlign: "center", verticalAlign: "middle" }),
  base("dia1", "diamond", 220, 0, 120, 120, { roundness: { type: 2 }, strokeStyle: "dashed" }),
  base("ell1", "ellipse", 400, 0, 140, 90, { fillStyle: "hachure", backgroundColor: "#ffc9c9", strokeStyle: "dotted" }),
  text("t2", "free text", 0, 160),
  linear("line1", "line", 0, 240, [[0, 0], [120, 40], [240, 0]], { roundness: { type: 2 } }),
]));

write("bound-arrows.excalidraw", scene([
  base("a", "rectangle", 0, 0, 120, 80, { boundElements: [{ id: "arr1", type: "arrow" }] }),
  base("b", "rectangle", 300, 0, 120, 80, { boundElements: [{ id: "arr1", type: "arrow" }, { id: "arr2", type: "arrow" }] }),
  base("c", "ellipse", 300, 200, 120, 80, { boundElements: [{ id: "arr2", type: "arrow" }] }),
  linear("arr1", "arrow", 120, 40, [[0, 0], [180, 0]], {
    startBinding: { elementId: "a", fixedPoint: [1, 0.5], focus: 0, gap: 1 },
    endBinding: { elementId: "b", fixedPoint: [0, 0.5], focus: 0, gap: 1 },
  }),
  linear("arr2", "arrow", 360, 80, [[0, 0], [0, 120]], {
    startBinding: { elementId: "b", fixedPoint: [0.5, 1], focus: 0, gap: 1 },
    endBinding: { elementId: "c", fixedPoint: [0.5, 0], focus: 0, gap: 1 },
    endArrowhead: "triangle", startArrowhead: "dot",
  }),
  linear("elbow1", "arrow", 0, 300, [[0, 0], [100, 0], [100, 60]], { elbowed: true, roundness: null, startBinding: null, endBinding: null, fixedSegments: null, startIsSpecial: null, endIsSpecial: null }),
]));

write("frame-group.excalidraw", scene([
  base("fr1", "frame", -20, -20, 360, 200, { name: "Frame A" }),
  base("g1", "rectangle", 0, 0, 100, 60, { groupIds: ["grp1"], frameId: "fr1" }),
  base("g2", "ellipse", 140, 0, 100, 60, { groupIds: ["grp1"], frameId: "fr1" }),
  base("loose", "diamond", 400, 0, 80, 80),
  base("mf1", "magicframe", 0, 300, 200, 120, { name: null }),
  base("emb1", "embeddable", 300, 300, 200, 120, { link: "https://example.com" }),
  base("ifr1", "iframe", 560, 300, 200, 120, { customData: { generationData: { status: "pending" } } }),
]));

const px = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
write("image.excalidraw", scene([
  base("img1", "image", 0, 0, 200, 200, { fileId: "file1", status: "saved", scale: [1, 1], crop: null }),
  base("img2", "image", 240, 0, 200, 200, { fileId: "file1", status: "saved", scale: [-1, 1], crop: { x: 0, y: 0, width: 1, height: 1, naturalWidth: 1, naturalHeight: 1 } }),
], { file1: { mimeType: "image/png", id: "file1", dataURL: "data:image/png;base64," + px, created: 1700000000000, lastRetrieved: 1700000000000 } }));

const fpts = Array.from({ length: 12 }, (_, i) => [i * 8, Math.round(20 * Math.sin(i / 2) * 100) / 100]);
write("freedraw.excalidraw", scene([
  base("fd1", "freedraw", 0, 0, 88, 40, { points: fpts, pressures: fpts.map((_, i) => Math.round((0.3 + 0.05 * i) * 100) / 100), simulatePressure: false, lastCommittedPoint: null }),
  base("fd2", "freedraw", 0, 100, 88, 40, { points: fpts, pressures: [], simulatePressure: true, lastCommittedPoint: null }),
]));

write("legacy-binding.excalidraw", scene([
  base("la", "rectangle", 0, 0, 120, 80, { boundElements: [{ id: "larr", type: "arrow" }] }),
  base("lb", "rectangle", 300, 0, 120, 80, { boundElements: [{ id: "larr", type: "arrow" }] }),
  linear("larr", "arrow", 120, 40, [[0, 0], [180, 0]], {
    startBinding: { elementId: "la", focus: 0, gap: 4 },
    endBinding: { elementId: "lb", focus: -0.1, gap: 4 },
  }),
]));
console.log("fixtures written");
