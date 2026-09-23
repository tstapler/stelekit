# Stack Research: Tag Suggestion Trigger Icon

## Icon Recommendation

**Use `Icons.AutoMirrored.Filled.Label`** for the "Suggest tags" button.

### Rationale

- **`Label`** is the standard Material3 icon for tags/labels. It renders as a price-tag/bookmark shape, universally understood as "label" or "tag" in note-taking apps.
- **`NewLabel`** (`Icons.Default.NewLabel`) is also available and conveys "add tag" semantics — may be even more discoverable than plain `Label` for a suggestion action.
- **`AutoAwesome`** (`Icons.Default.AutoAwesome`) is available and conveys AI/sparkle-suggestion semantics — a good alternative if the intent is to emphasize the AI-driven nature of the suggestion.
- **`Sell`** (`Icons.Default.Sell`) is a filled tag icon that could also work but is less commonly associated with labels in note apps.

### Confirmed available in `compose.materialIconsExtended` (verified in jar)

All of the following classes exist in the deployed jar at:
`jetified-material-icons-extended-release/jars/classes.jar`

| Icon | Class in jar | Import path |
|---|---|---|
| Label (primary recommendation) | `automirrored/filled/LabelKt.class` | `androidx.compose.material.icons.automirrored.filled.Label` |
| NewLabel | `filled/NewLabelKt.class` | `androidx.compose.material.icons.filled.NewLabel` |
| AutoAwesome | `filled/AutoAwesomeKt.class` | `androidx.compose.material.icons.filled.AutoAwesome` |
| Sell | `filled/SellKt.class` | `androidx.compose.material.icons.filled.Sell` |

> **Pitfall:** `Label` is in the `automirrored` package, not the standard `filled` package. Use `Icons.AutoMirrored.Filled.Label`, NOT `Icons.Default.Label` (which does not exist). `NewLabel`, `AutoAwesome`, and `Sell` are all in standard `filled` — use `Icons.Default.*` for those.

## Existing Icon Import Pattern in MobileBlockToolbar.kt

The file already uses:
```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreHoriz
```

To add the tag suggestion button, append:
```kotlin
import androidx.compose.material.icons.automirrored.filled.Label
```

And use it as:
```kotlin
Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null)
```

## Dependency Version

- `compose.materialIconsExtended` is declared in `commonMain` dependencies (managed by the JetBrains Compose Multiplatform plugin).
- Android also uses `androidx.compose.material3:material3:1.4.0` for Android-specific targets.
- The extended icons dependency is **already on the KMP classpath** — no new dependency is needed.

## Icons That Do NOT Exist

- **`Icons.Default.Label`** — does not exist; `Label` is only in the `automirrored` package.
- **`Icons.Default.Sparkle` / `Icons.Default.Sparkles`** — no `Sparkle` or `Sparkles` icon found in the jar; do not use.
- **`Icons.Default.Tag`** — no `Tag` icon found in the filled package; do not use.
