# Closet source corpus summary (sanitised: dimension facts, SVG structure, parsing grammar, golden fixtures)

This file keeps only what the plan needs: the closet dimension facts, how the drawings are structured, the length grammar and the golden-fixture list. Page titles, file names, other projects, costs, product details and verbatim prose are deliberately absent; the private original lives outside the repo (`~/.cache/stelekit-private/home-project-sketching/`). Zone owners are the roles `left`/`right`; the project slug is the opaque `proj-closet-1`. Values below are numeric fragments only.

Date: 2026-11-09. Nothing in the owner's wiki was modified.

Source roles (all VERIFIED by reading): T = a markdown table, N = narrative prose on the same topic, Y = a YAML dimension model, E = an elevation SVG, S = a section SVG, Epng/Spng = raster renders of E and S, Lpng = a raster-only layout render. There is no layout SVG.

## 1. Inventory of closet dimensions (value as written, by source role)

Legend: Y yaml, T table, N narrative, SVG-text (visible `<text>`), SVG-geom (derivable only from path coordinates), PNG (pixels).

| # | Dimension | Sources and values as written |
|---|---|---|
| 1 | Opening width | Y `136`; T `136" (11' 4")`; N `136"`; SVG-text `Total Width: 136"`; SVG-geom E x extent 136.0 |
| 2 | Height rear | Y `40.875`, comment `40 7/8"`; T `40 7/8"`; SVG-text `40.88" (Rear)` (rounded, lossy); S-geom 40.875 |
| 3 | Height front | Y `43.3125`, comment `43 5/16"`; T `43 5/16"`; SVG-text `43.31" (Front)` (lossy); geom 43.3125 |
| 4 | Working height | Y `41`; N `~41"`, `41" ±1/16"` |
| 5 | Usable depth | Y `24`; T `~24"`; SVG-text S `24" Usable Depth`; S-geom x range 0..24 |
| 6 | Zone width left-long | Y `33.4375`; T `33 7/16"`; N `33 7/16"`; SVG-text `33 7/16"` |
| 7 | Zone width left-shirts | Y `22.5`; T `22 1/2"`; SVG-text `22 1/2"` |
| 8 | Zone width shared-shelves | Y `24.1875`; T `24 3/16"`; SVG-text `24 3/16"` |
| 9 | Zone width right-tops | Y `22.5`; T `22 1/2"`; SVG-text `22 1/2"` |
| 10 | Zone width right-long | Y `33.375`; T `33 3/8"`; N `33 3/8"`; SVG-text `33 3/8"` |
| 11 | Middle three zones sum | N `22 1/2" + 24 3/16" + 22 1/2" = 69 3/16"` (derived; not in Y) |
| 12 | Rod height short | Y `36`; T `36"`; N `36"`; SVG-text `36" Rod` |
| 13 | Rod height long | Y `38`; T `38"`; SVG-text `38" Rod` |
| 14 | Rod setback | S-text `12" from back wall`; S-geom circle centre x = 12.0; not in Y |
| 15 | Rod diameter | Y `radius: 0.625` (1.25" OD); N `1-1/16" OD` and `1-3/8" OD` variants; S-geom circle radius 0.625 (see D5) |
| 16 | Shelf count and gap | Y `shelf_count: 4`; T `8.2"` gaps; E-geom four bands, pitch 8.2 |
| 17 | Shelf thickness | Y `0.75`; geom 0.75 bands |
| 18 | Shelf depth | S-text `22 1/4" Depth`; N two other depth figures (see D4) |
| 19 | Standard positions (6) | T/N `0", 33 7/16", 55 15/16", 80 1/8", 102 5/8", 136"`; E-geom upright strips |
| 20 | Standard spacing | N average `27"` (136/5 = 27.2) |
| 21 | Stud offsets | N `1.5", 17.5", ..., 129.5"` (16" pitch, 1.5" offset), and decimal roundings `55.9", 80.1", 102.6"` |
| 22 | Lower cabinet width | Y `136` |
| 23 | Lower cabinet usable height | Y `23.125`; T `23 1/8"`; SVG-text `23.12" Cabinet H` (lossy); S-geom 23.125 |
| 24 | Lower cabinet total height | Y `25.5625` |
| 25 | Lower cabinet bay width | Y `30.875`; N `30 7/8"` |
| 26 | Hang math | Y `niche_hang_depth: 39`, `cabinet_depth: 23.125`, `total_hang: 62.125`; N `~39"`, `~62"` |
| 27 | Rod clearance below | T `8"` for the 36" rod; N small clearances for hanging garments |

Count: 27 listed quantities plus the 6 standard positions and 9 stud offsets as list-valued dimensions; 24 or more have at least two sources.

## 2. Discrepancies (neutral descriptions)

- D1 (left/right orientation). The written order of the two outer zones in T and Y is the reverse of their left-to-right position in E: the drawing is a view from the opposite side (x axis negated: paths run from `0.0` to `-136.0`, group `scale(11.06, -11.06) translate(...)`). Needs a sheet property for view direction; the importer must raise `mirrored-view`, not a numeric conflict.
- D2 (symmetry claim vs layout render). Lpng bay labels `31/25/24/25/31` sum to 136 but do not match the table zones (a stale raster; manual only).
- D3 (rod-carrier count). Three prose and bill-of-materials statements give 8, 10 and 12.
- D4 (shelf depth). Three different depth figures (22 1/4, 20, ~22) against a 24 usable depth.
- D5 (rod diameter). Y `1.25" OD` against `1-1/16"` and `1-3/8"` in narrative.
- D6 (a non-dimensional estimate that differs between two documents; detail kept private; manual).
- D7 (lossy rounding in labels). `43.31"`, `40.88"`, `23.12"` against exact `43 5/16"`, `40 7/8"`, `23 1/8"`; a reconciler needs a tolerance tied to label precision (0.005" for 2-dp labels).
- D8 (cabinet width vs bays). `4 x 30.875 = 123.5` against 136; the 12.5" of dividers is not stated (missing derivation, not a conflict); the drawing shows five lower bays against four in prose.
- D9 (niche height model). `41"` working height against front 43 5/16 and rear 40 7/8 (sloped ceiling); a trimmed 41" standard is 1/8" over the rear height (possible real conflict; no golden case).
- D10 (stud list). Decimal roundings `55.9, 80.1, 102.6` of `55 15/16, 80 1/8, 102 5/8` (consistent, lossy).
- D11 (rod-ceiling clearance). `41 - 36 = 5` and `41 - 38 = 3` hold for the working height but become `4 7/8` and `2 7/8` at the rear height.
- D12 (a dangling wiki link; no dimension content).

No numeric conflict exists between Y, T and SVG-text for the five zone widths apart from D1. Seeded conflicts are injected into a fixture copy (section 5).

## 3. What the SVGs expose structurally

Both SVGs are CadQuery `exportSVG` output post-processed to append one `<g fill=... font-family=... font-size="14|12">` block of `<text>` elements before `</svg>`.

- Root: `<svg xmlns=... width="1800.0" height="813.77...">` (E); `width="1100.0" height="2365.42..."` (S). No `viewBox`, `id`, `class`, `data-*`, `<title>`, `<desc>` or `<metadata>`. Elements are unnamed paths (E: 245 `<path>`, each `M x,y L x,y`; S: two tessellated circle paths with about 50 `L` points).
- Transform: one `<g transform="scale(11.0638..., -11.0638...) translate(149.346..,-45.870..)">` in E (y-flipped; drawing units inches; x negated by model orientation); S: `scale(34.2288..., 34.2288...) translate(3.88..,44.64..)`. Pixels per inch: 11.0638 (E), 34.2289 (S); `stroke-width` equals 1 px / scale.
- Text elements: absolute pixel coordinates in the untransformed space with `text-anchor`. E has 11 `<text>` (the five zone widths, `Total Width: 136"`, `43.31" (Front)`, `40.88" (Rear)`, `Ceiling Heights`, `38" Rod`, `36" Rod`); S has 8 (`24" Usable Depth`, the two heights, two rod labels with `12" from back wall`, the shelf-depth label, `23.12" Cabinet H` twice).
- Extraction: labels parse with one grammar (fraction labels exact, decimal labels lossy); binding a label to geometry has no id link and needs proximity rules plus a name hint (`Rod`, `Total Width`, `Front/Rear`, `Cabinet H`); geometry-derived dimensions are exact in inches given the transform (width 136.0, heights 43.3125/40.875, rod centres, shelf bands, upright strips) with float noise (for example `7.824999999999999`), so snap to 1/64 or use epsilon 1e-6; mirroring is sheet metadata (D1); nothing in the SVG says the unit is inches (calibrate from `Total Width: 136"` against the scale: 136 x 11.0638 = 1504.7 px, or from a named known dimension).
- PNGs: Epng/Spng carry the same text as E/S; Lpng is raster-only. Manual tagging or OCR only.
- Recommendation (interoperate, not replace the owner's pipeline): the renderer could emit `id` and `data-dim-*` attributes on the label `<text>` and `<g>` (Story 4.6, written outside the repo).

## 4. Length grammar and disambiguation

Formats seen: mixed fractions with inch mark (`33 7/16"`), tilde estimates (`~24"`), feet-and-inches equivalents in parentheses (`136" (11' 4")`), bold-wrapped table values, bare decimals (`33.4375"`, `55.9"`), ranges (`55-58"`, en-dash forms), hyphenated mixed numbers (`1-1/16"`), tolerances (`±1/16"`), unicode vulgar fractions (`½ ¼ ¾ ⅛ ⅜ ⅝ ⅞`), screw-gauge forms (`#10 × 2.5"`), architectural feet-inches with hyphen (`9'-6"`), loose prose units (`ft`), photo-derived estimates (confidence matters), YAML decimals with the fraction in a trailing comment (a redundant source of the same scalar), and 2-dp decimal SVG labels.

Canonical value: exact rational inches plus `precision` metadata used for tolerance.

```
length      := sign? ( feet_inches | inches_only | feet_only | metric )
feet_inches := feet SEP? inches_expr                 ; 5' 4 1/4"  | 5'-4 1/4" | 5'4" | 11' 4"
feet_only   := feet                                  ; 8'  | 10 ft
feet        := number ( "'" | "ft" | "feet" | "foot" )
inches_only := inches_expr
inches_expr := number_expr ( '"' | "in" | "inch" | "inches" | "″" )?
number_expr := mixed | fraction | decimal | integer
mixed       := integer ( WS | "-" ) fraction | integer vulgar   ; 33 7/16 | 1-1/16 | 13½
fraction    := integer "/" integer | vulgar
decimal     := integer? "." digits
SEP         := WS | "-" | "–" | ε
qualifier   := "~" | "≈" | "approx" | "min" | "max" | "±" length | "VIF" | "TYP"
range       := length ( "-" | "–" | "—" | "to" ) length  ; second inherits the first's unit if omitted
tolerance   := "±" fraction_or_decimal '"'
aggregate   := length ( "x" | "×" ) length              ; a size list, not arithmetic
```

Disambiguation rules:
1. A hyphen between an integer and a fraction (`\d+-\d+/\d+`) is a mixed number; a hyphen after a feet mark (`9'-6"`) is a separator; `\d+-\d+"?` with no fraction is a range.
2. `5' 4 1/4"` = 64.25"; `33 7/16"` = 33.4375". `136" (11' 4")` yields two values that must be equal (11 x 12 + 4 = 136); a parenthetical equivalent is a consistency check, not a second dimension.
3. 2-dp decimals from SVG labels carry precision 0.005; fractions carry 1/denominator; decimals with 4 or more places whose value is dyadic (`33.4375`) are exact.
4. Keep the exact rational; display with denominator <= 16. `55.9"` parses to 559/10 (non-dyadic, precision 0.05) and compares by tolerance.
5. `x` / `×` between lengths is a size list; screw gauges need an allow-list; structural member codes (a letter-digit prefix followed by a size) stay text. Prose extraction is anchored (nearest noun phrase or table header), carries `prose` provenance and lower confidence.
6. `~`, `±`, `min`, `max`, `VIF` map to metadata (`approx`, `tolerance`, `bound`, `status`), not to the value.
7. Unmarked units in a column titled `Width`/`Value` default to inches only if the sheet unit is inches.
8. Parse unicode vulgar fractions and `′ ″`; normalise `–` `—` to `-`.

Table extraction: rows whose first column is a name and a cell parses as `length`; Logseq nesting (a table indented under a bullet, separator row on the next line) must be tolerated; bold markers are stripped; the zone table also carries a rod-height column that is a different dimension kind than width, so dimension identity = (row key, column header).

## 5. Golden fixture list

Fixture inputs: the YAML model, the tables, E, S and the prose. Expected counts derive from the numbers above.

Dimensions (at least 10 with at least 2 sources each; name -> exact value -> source roles):
1. `niche.width` = 136" -> Y, T, N, E-text, E-geom
2. `zone.left-long.width` = 33 7/16" -> Y, T, N, E-text
3. `zone.left-shirts.width` = 22 1/2" -> Y, T, E-text
4. `zone.shelves.width` = 24 3/16" -> Y, T, E-text
5. `zone.right-tops.width` = 22 1/2" -> Y, T, E-text
6. `zone.right-long.width` = 33 3/8" -> Y, T, N, E-text
7. `niche.height.rear` = 40 7/8" -> Y, T, E-text (lossy), S-text, S-geom
8. `niche.height.front` = 43 5/16" -> Y, T, E-text (lossy), S-text, S-geom
9. `rod.short.height` = 36" -> Y, T, N, E-text, S-text
10. `rod.long.height` = 38" -> Y, T, N, E-text, S-text
11. `niche.depth` = 24" -> Y, T, S-text
12. `lowercab.height` = 23 1/8" -> Y, T, S-text (lossy)
13. `lowercab.bay` = 30 7/8" -> Y, N
14. `shelf.gap` = 8.2" -> T, N, E-geom
15. `standards.positions` = the six values 0, 33 7/16, 55 15/16, 80 1/8, 102 5/8, 136 -> T, N, E-geom

Invariants:
- I1 zone sum: 33 7/16 + 22 1/2 + 24 3/16 + 22 1/2 + 33 3/8 = 136" exactly
- I2 middle sum: 22 1/2 + 24 3/16 + 22 1/2 = 69 3/16"
- I3 outer + middle: 33 7/16 + 69 3/16 + 33 3/8 = 136"
- I4 standard positions are cumulative zone sums (55 15/16, 80 1/8, 102 5/8, and 136 - 33 3/8 = 102 5/8)
- I5 rod clearance: 41 - 36 = 5; 41 - 38 = 3; 36 - 28 = 8
- I6 stud list: offset 1.5, pitch 16 gives 1.5 .. 129.5 (9 values); 33 7/16 is within 1/16" of 33.5
- I7 `136" = 11' 4"`
- I8 hang: 39 + 23 1/8 = 62 1/8
- I9 shelves: 4 shelves, pitch 8.2 (7.825, 16.025, 24.225, 32.425)

Seeded and real conflicts:
- C1 (seeded): the table says `33 3/8"` for left-long while Y and E say `33 7/16"`: conflict naming both sources, difference 1/16", and I1 fails (sum 135 15/16").
- C2 (seeded): E-text for the long rod changed to `37" Rod` against Y/T `38`: difference 1".
- C3 (real): orientation D1 -> warning `mirrored-view`, not a numeric conflict.
- C4 (real): rod diameter D5, `1.25"` against `1-1/16"`: difference 3/16".
- C5 (real): rod-carrier count D3, 8 vs 10 vs 12.
- C6 (tolerance, must NOT conflict): `43.31"` vs `43 5/16"` (difference 0.0025 < 0.005) and `23.12"` vs `23 1/8"`.

Parser golden cases: `33 7/16"` -> 33.4375; `5' 4 1/4"` and `5'-4 1/4"` -> 64.25; `11' 4"` -> 136; `9'-6"` -> 114; `1-1/16"` -> 1.0625; `31-5/8"` -> 31.625; `13½` -> 13.5; `~24"` -> 24 approx; `55-58"` -> range; `±1/16"` -> tolerance; `55.9"` -> 559/10, precision 1/20; a structural member code and `#10 × 2.5"` -> not a length.

Expected counts: 15 dimensions with at least 2 sources; 9 invariants (I1-I9); 2 seeded + 3 real conflicts + 1 negative control.
