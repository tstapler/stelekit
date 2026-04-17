# Migration Plan: Extensions & Plugins

## 1. Discovery & Requirements
Logseq has a rich plugin ecosystem. Migrating the core app to KMP presents a significant challenge: existing plugins are written in JavaScript and expect a DOM/Electron environment.

### Existing Artifacts
- `src/main/frontend/components/plugins.cljs`: UI for plugin management.
- `src/main/frontend/handler/plugin.cljs`: Core plugin loader and API bridge.

### Functional Requirements
- **Plugin Loading**: Load/Unload plugins from marketplace or local.
- **API Exposure**: Provide APIs for plugins to manipulate the graph, UI, and editor.
- **Sandboxing**: Prevent plugins from crashing the app or stealing data.

### Non-Functional Requirements
- **Compatibility**: Support existing JS plugins (critical for ecosystem).
- **Performance**: Plugins should not block the main UI thread.

## 2. Architecture & Design (KMP)

### Strategy: The "Host" Model
Since we cannot rewrite 3rd party plugins in Kotlin, we must provide a **JavaScript Host Environment**.

- **Desktop**: Continue using a hidden Electron Window or a `WebView` to run plugin JS.
- **Mobile**: Use a headless `WebView` or `QuickJS`.

### Logic Layer (Common)
- **PluginManager**: Manages lifecycle (install, enable, disable).
- **PluginBridge**: A message-passing layer (JSON-RPC) between KMP Core and the JS Host.
    - KMP sends events: `onBlockChanged`, `onPageLoaded`.
    - JS sends commands: `createBlock`, `showMsg`.

### UI Layer (Compose Multiplatform)
- **Component**: `PluginSettings` UI.
- **Component**: `Marketplace` UI.
- **Integration**: Allow plugins to render UI elements (via `WebView` overlays or native widgets if a new KMP API is defined).

## 3. Proactive Bug Identification (Known Issues)

### 🐛 Architecture: API Drift [SEVERITY: Critical]
- **Description**: The KMP internal architecture will differ from the old CLJS architecture. The existing Plugin API relies on specific CLJS structures.
- **Mitigation**: Create an **Adapter Layer** in the JS Host that mimics the old API but translates calls to the new KMP Bridge.

### 🐛 Performance: Bridge Latency [SEVERITY: High]
- **Description**: Excessive JSON serialization between KMP and JS Host will cause lag.
- **Mitigation**: Batch events. Optimize the serialization format. Avoid synchronous calls from JS to KMP where possible.

### 🐛 Security: WebView Permissions [SEVERITY: Medium]
- **Description**: Plugins running in a WebView might try to access file system or network unrestricted.
- **Mitigation**: Strict CSP (Content Security Policy). Intercept all native calls via the Bridge.

## 4. Implementation Roadmap

### Phase 1: The Bridge
- [ ] Define the JSON-RPC protocol between KMP and JS.
- [ ] Implement `PluginBridge` in Kotlin.

### Phase 2: JS Host (Desktop)
- [ ] Set up the JS execution environment (likely keeping part of Electron for now).
- [ ] Implement the "Adapter Layer" to mock old API.

### Phase 3: Marketplace
- [ ] Port the Plugin Marketplace UI to Compose.
- [ ] Implement plugin installer (unzip, validate).

## 5. Migration Checklist
- [ ] **Logic**: Plugins can load and execute JS.
- [ ] **Logic**: Bridge successfully passes messages.
- [ ] **UI**: Plugin settings page works.
- [ ] **Parity**: Top 10 most popular plugins work (Smoke Test).

