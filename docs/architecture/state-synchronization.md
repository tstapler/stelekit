# State Synchronization Architecture

## The Problem: Reactive Replay
In a reactive KMP application, data often flows in a loop: 
`UI (Local State) -> ViewModel (Debounce) -> DB -> Repository (Flow) -> UI (Property Update)`

Because of the **Debounce** and **Disk I/O lag**, the "Property Update" arriving at the UI is often "stale" compared to the user's current typing speed.

## The Pattern: Filtered Mirror
To prevent stale updates from wiping out local user input, components with local state must distinguish between **Self-Inflicted Updates** and **External Updates**.

### Implementation Strategy
1. **Track Last Sent**: Maintain a `lastSentContent` variable that stores the value most recently passed to the `onContentChange` callback.
2. **Conditional Sync**: Inside the `LaunchedEffect` or `didUpdate` block that listens to the repository property:
   - If `incomingProperty == lastSentContent`: **IGNORE**. This is just the repository catching up to a previous local change.
   - If `incomingProperty == localState`: **IGNORE**. Everything is already in sync.
   - Otherwise: **APPLY**. This is a genuine external change (Undo, Redo, or Sync).

### Code Example (Compose)
```kotlin
var localState by remember { mutableStateOf(externalProperty) }
var lastSent by remember { mutableStateOf(externalProperty) }

LaunchedEffect(externalProperty) {
    if (externalProperty != lastSent && externalProperty != localState) {
        localState = externalProperty
        lastSent = externalProperty
    }
}

TextField(
    value = localState,
    onValueChange = {
        localState = it
        lastSent = it // Update immediately to filter the upcoming round-trip
        onExternalUpdate(it)
    }
)
```

## When to use this
- Block editors
- Page title editors
- Property key/value editors
- Any field with >100ms persistence lag
