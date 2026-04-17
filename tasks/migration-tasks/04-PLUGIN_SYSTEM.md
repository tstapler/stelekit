# Task: Complete Plugin System Implementation

## Overview
Implement a comprehensive plugin system that allows users to extend Logseq functionality through JavaScript-based plugins, maintaining compatibility with existing Logseq plugins while providing a modern KMP-based architecture.

## Current State
- KMP has basic `PluginSystem.kt` with minimal interfaces
- Missing: JS bridge, plugin lifecycle, command registration, UI integration

## Implementation Tasks

### 1. **JavaScript Plugin Bridge**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/plugins/bridge/`

**Components**:
- `JsPluginBridge.kt` - Main bridge between KMP and JavaScript
- `JsRuntime.kt` - JavaScript runtime management
- `MessageRouter.kt` - Route messages between KMP and JS
- `ApiProvider.kt` - Provide KMP APIs to JavaScript
- `SecurityManager.kt` - Plugin sandbox and permissions

**Platform-Specific Implementations**:
- `JsBridgeDesktop.kt` - JVM-specific implementation (Node.js/JavaFX)
- `JsBridgeWeb.kt` - Web implementation (JavaScriptCore/Web Workers)
- `JsBridgeAndroid.kt` - Android implementation (Android WebView)
- `JsBridgeIOS.kt` - iOS implementation (JavaScriptCore)

**Features**:
- Secure plugin sandboxing
- API permission management
- Memory isolation between plugins
- Error handling and recovery
- Performance monitoring

### 2. **Plugin Lifecycle Management**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/plugins/lifecycle/`

**Components**:
- `PluginLoader.kt` - Load and initialize plugins
- `PluginRegistry.kt` - Register and manage active plugins
- `PluginValidator.kt` - Validate plugin manifests and code
- `DependencyResolver.kt` - Handle plugin dependencies
- `PluginUpdater.kt` - Plugin updates and versioning

**Features**:
- Plugin installation and removal
- Version management
- Dependency resolution
- Hot-reloading for development
- Plugin health monitoring

### 3. **Plugin API Surface**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/plugins/api/`

**Components**:
- `AppApi.kt` - App and graph management APIs
- `DbApi.kt` - Database query and manipulation APIs
- `EditorApi.kt` - Editor manipulation APIs
- `UiApi.kt` - UI customization APIs
- `StorageApi.kt` - Plugin storage and configuration APIs

**API Categories**:
```kotlin
// App APIs
class AppApi {
    suspend fun showInfo(): AppInfo
    suspend fun getUserConfigs(): Map<String, Any>
    suspend fun getCurrentGraph(): GraphInfo
    suspend fun setThemeMode(mode: ThemeMode)
}

// Database APIs  
class DbApi {
    suspend fun query(query: String): List<Map<String, Any>>
    suspend fun createPage(name: String): Page
    suspend fun createBlock(pageId: String, content: String): Block
    suspend fun upsertBlockProperty(blockId: String, key: String, value: Any)
}

// Editor APIs
class EditorApi {
    suspend fun insertAtCursor(text: String)
    suspend fun getCurrentBlock(): Block?
    suspend fun openPage(pageName: String)
    suspend fun selectBlocks(blockIds: List<String>)
}
```

### 4. **Plugin Storage & Configuration**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/plugins/storage/`

**Components**:
- `PluginStorage.kt` - Persistent storage for plugins
- `ConfigManager.kt` - Plugin configuration management
- `SettingsManager.kt` - Plugin settings UI
- `DataStore.kt` - Data persistence layer
- `BackupManager.kt` - Plugin data backup and restore

**Features**:
- Isolated storage per plugin
- Settings validation and migration
- Import/export plugin configurations
- Data synchronization across devices
- Storage quota management

### 5. **Command & UI Registration**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/plugins/ui/`

**Components**:
- `CommandRegistry.kt` - Register slash commands
- `MenuItemRegistry.kt` - Register UI menu items
- `ThemeRegistry.kt` - Register custom themes
- `IconRegistry.kt` - Register custom icons
- `ShortcutsManager.kt` - Register custom shortcuts

**Features**:
- Custom slash commands
- Context menu items
- Toolbar buttons
- Custom UI panels
- Keyboard shortcuts

### 6. **Plugin Development Tools**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/plugins/devtools/`

**Components**:
- `PluginDebugger.kt` - Debug plugin execution
- `ConsoleApi.kt` - Console output and logging
- `Profiler.kt` - Plugin performance profiling
- `TestRunner.kt` - Plugin testing framework
- `DocumentationGenerator.kt` - Auto-generate API docs

**Features**:
- Step-by-step debugging
- Performance profiling
- API documentation
- Development console
- Hot module replacement

## Integration Points

### With Existing KMP Code:
- Extend current `PluginHost` class
- Integrate with `LogseqViewModel` for state
- Use repository layer for data access
- Connect to notification system

### With UI Components:
- Add plugin settings to `Settings.kt`
- Integrate with `CommandPalette.kt` for plugin commands
- Update `Sidebar.kt` for plugin UI items

## Plugin Manifest Format

### package.json Extension:
```json
{
  "logseq": {
    "id": "my-plugin",
    "name": "My Plugin",
    "version": "1.0.0",
    "main": "index.js",
    "description": "Plugin description",
    "author": "Author name",
    "icon": "icon.png",
    "permissions": [
      "graph:read",
      "page:write",
      "storage:read"
    ],
    "settings": [
      {
        "key": "apiKey",
        "title": "API Key",
        "type": "string",
        "default": ""
      }
    ]
  }
}
```

## Security Model

### Permission System:
- **graph:read** - Read graph data
- **graph:write** - Modify graph data
- **page:create** - Create new pages
- **block:create** - Create new blocks
- **storage:read** - Read plugin storage
- **storage:write** - Write plugin storage
- **network:request** - Make network requests
- **ui:customize** - Modify UI elements

### Sandboxing:
- Isolate plugin execution
- Limit API access based on permissions
- Prevent access to system resources
- Monitor for malicious behavior

## Migration from ClojureScript

### Files to Reference:
- `src/main/logseq/api.cljs` - Main API surface
- `src/main/frontend/handler/plugin.cljs` - Plugin handling
- `src/main/frontend/components/plugins.cljs` - Plugin UI
- `src/main/logseq/api/db-based.cljs` - Database APIs

### API Functions to Port:
- `q` - Query database
- `create_page` - Create new page
- `append_block_in_page` - Add block to page
- `register_plugin_slash_command` - Register commands
- `show_themes` - Theme management

## Testing Strategy

### Unit Tests:
- Test plugin loading and unloading
- Test API permission enforcement
- Test message routing between KMP and JS
- Test storage isolation

### Integration Tests:
- Test with real plugin examples
- Test API compatibility with existing plugins
- Test plugin updates and migrations
- Test concurrent plugin execution

### Security Tests:
- Test permission bypass attempts
- Test resource exhaustion attacks
- Test malicious plugin detection
- Test data isolation between plugins

## Success Criteria

1. Existing Logseq plugins work without modification
2. Plugin sandboxing prevents malicious behavior
3. API provides full plugin functionality
4. Plugin development workflow is smooth
5. Performance impact is minimal
6. Security model is robust
7. Documentation is comprehensive

## Dependencies

### External Libraries:
- JavaScript runtime (platform-specific)
- Security and sandboxing libraries
- JSON parsing and validation
- HTTP client for network requests
- Plugin manifest parsing

### Platform-Specific:

#### Desktop (JVM):
- Node.js integration or Nashorn
- File system access APIs
- Desktop window management

#### Web:
- Web Workers for isolation
- Local storage APIs
- Browser security policies

#### Mobile:
- WebView JavaScript bridge
- Mobile storage APIs
- App sandbox compliance

## Development Workflow

### For Plugin Developers:
1. Create `package.json` with Logseq manifest
2. Implement plugin in JavaScript/TypeScript
3. Use provided APIs for Logseq interaction
4. Test with built-in development tools
5. Publish to plugin marketplace

### For Core Developers:
1. Maintain API compatibility
2. Add new APIs based on plugin needs
3. Monitor plugin performance and security
4. Provide migration guides for API changes
5. Support plugin developer community
