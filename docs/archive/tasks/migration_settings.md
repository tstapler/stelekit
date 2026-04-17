# Migration Plan: Settings & Preferences

## 1. Discovery & Requirements
Settings manage user preferences, theme selection, keybindings, and feature flags.

### Existing Artifacts
- `src/main/frontend/components/settings.cljs`: Main settings UI.
- `src/main/frontend/components/plugins_settings.cljs`: Plugin-specific settings.

### Functional Requirements
- **Storage**: Persist user preferences (e.g., "Dark Mode", "Font Size").
- **Scope**: Global settings vs. Graph-specific settings (config.edn).
- **UI**: Categorized settings screen (General, Editor, View, etc.).
- **Migration**: Import existing settings from `localStorage` or `config.edn`.

### Non-Functional Requirements
- **Reactivity**: Changing a setting (e.g., Font Size) should immediately update the UI.
- **Type Safety**: Settings should be typed (Boolean, Int, Enum) to prevent invalid states.

## 2. Architecture & Design (KMP)

### Logic Layer (Common)
- **SettingsRepository**: Interface for reading/writing settings.
    - Implementation: **MultiplatformSettings** (wrapper around SharedPreferences/NSUserDefaults) for app prefs.
    - Implementation: **ConfigParser** for `config.edn` (Graph settings).
- **SettingsService**: Exposes settings as `StateFlow<T>` for reactive UI updates.

### UI Layer (Compose Multiplatform)
- **Component**: `SettingsScreen`.
- **Component**: `SettingItem` (Switch, Slider, Dropdown).
- **Component**: `KeymapEditor` (Complex UI for rebinding keys).

## 3. Proactive Bug Identification (Known Issues)

### 🐛 Data: Config Drift [SEVERITY: Medium]
- **Description**: Users manually editing `config.edn` might introduce syntax errors or invalid values.
- **Mitigation**: Implement a robust EDN parser with error recovery. Fallback to defaults if parsing fails. Show a warning in UI.

### 🐛 UX: Theme Flickering [SEVERITY: Low]
- **Description**: If the theme setting loads after the UI renders, the app might flash white before turning dark.
- **Mitigation**: Load critical display settings (Theme, Font Scale) synchronously at app startup before `setContent`.

## 4. Implementation Roadmap

### Phase 1: Storage Layer
- [ ] Implement `SettingsRepository` using MultiplatformSettings.
- [ ] Implement `ConfigParser` for EDN files.

### Phase 2: UI Implementation
- [ ] Build `SettingsScreen` layout.
- [ ] Implement basic settings (Theme, Language).

### Phase 3: Advanced Settings
- [ ] Implement Keymap editor.
- [ ] Implement Plugin settings generator (rendering settings based on plugin schema).

## 5. Migration Checklist
- [ ] **Logic**: Settings persist across restarts.
- [ ] **Logic**: `config.edn` is parsed correctly.
- [ ] **UI**: Changing theme updates immediately.
- [ ] **Parity**: All legacy settings are available.

