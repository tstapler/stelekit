# Stelekit Design Brief

## Concept

The stele — an upright stone slab used across civilizations (Mesopotamia, Egypt, Greece, Rome, Mesoamerica) to inscribe knowledge meant to endure. Not decorative. Not digital. Material.

The logo is a trio of steles: one large stone in the foreground flanked by two smaller ones behind it, all rising from a shared plinth. The arrangement suggests a collection — a graph of interconnected knowledge — not a single isolated note.

## Mark (Icon)

**Form**: Three upright stone slabs, slightly tapered (wider at base, narrower at crown). Carved horizontal bands on the front stele simulate inscription text. The two background steles are lighter in tone to suggest depth.

**Do**:
- Keep the silhouette simple enough to read at 16×16 pixels
- Design monochrome-first; color is a stone-texture application, not a gradient
- The taper should be subtle — the mark should not read as a tombstone or triangle

**Do not**:
- No gradients
- No drop shadows
- No 3D effects
- No corporate blues or greens — use the brand palette only

## Wordmark

**Text**: `stelekit` — lowercase, no camelCase, no space

**Type**: A geometric serif or inscribed letterform that evokes chiseled stone. References: Trajan, Cormorant, or a geometric sans with structural weight. Should feel hewn, not rounded.

**Weight**: Regular to Medium. Not bold.

**Spacing**: Slightly tracked (letter-spacing: 0.02–0.04em) to give the wordmark a considered, carved quality.

## Color Palette

| Token | Hex | Usage |
|---|---|---|
| `StoneBackground` | `#282828` | Dark background, adaptive icon bg |
| `GraniteSurface` | `#3C3836` | Wordmark on light bg |
| `WornStone` | `#928374` | Front stele fill |
| `PaleStone` | `#B8AFA0` | Back steles fill |
| `PatinaAccent` | `#83A598` | Accent highlight |
| `SandText` | `#EBDBB2` | Text on dark bg |

No gradients. The mark works in a single flat color on any background.

## Asset Directory

```
assets/brand/
├── svg/
│   ├── stelekit-mark.svg          # Icon only (512×512)
│   ├── stelekit-wordmark.svg      # Wordmark only
│   ├── stelekit-lockup.svg        # Horizontal: icon + wordmark (1024×512)
│   ├── stelekit-lockup-stacked.svg # Stacked: icon above wordmark (512×640)
│   └── og-image.svg               # GitHub social preview (1200×630)
├── png/
│   ├── icon_16.png … icon_512.png # Standard icon sizes
│   ├── apple-touch-icon.png       # 180×180, iOS Safari
│   ├── og-image.png               # 1200×630, Open Graph / GitHub preview
│   └── favicon.ico                # Multi-size: 16, 32, 48
└── DESIGN_BRIEF.md                # This file
```

## Font License

The wordmark uses **MedievalSharp** by Wojciech "wmk69" Kalinowski.

- License: SIL Open Font License 1.1 (OFL)
- Copyright: Copyright (c) 2011, wmk69 (wmk69@o2.pl)
- Source: https://github.com/wmk69/Medieval-Sharp
- Full license text: `assets/brand/fonts/OFL-MedievalSharp.txt`

The font is embedded as base64 in all wordmark SVGs. Redistribution of those SVGs is permitted under OFL 1.1 provided this copyright notice is preserved.
