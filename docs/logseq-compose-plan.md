# Logseq Kotlin Compose - Re-implementation Plan

**Location**: `/home/tstapler/Programming/logseq-kmp/kmp/`

A complete re-implementation of Logseq knowledge management platform using:
- **Kotlin 2.0** - Modern, type-safe language
- **Compose Desktop** - Native Kotlin UI framework
- **SQLDelight 2.0** - Type-safe SQLite (already implemented)
- **Clean Architecture** - Maintainable, testable code

## Project Structure (within existing kmp module)

```
logseq-kmp/
├── kmp/
│   ├── src/
│   │   ├── jvmMain/kotlin/com/logseq/
│   │   │   ├── Main.kt                    # App entry point
│   │   │   ├── LogseqApp.kt               # Application scope
│   │   │   ├── DesktopPlatform.kt         # Desktop-specific
│   │   │   ├── file/
│   │   │   │   ├── FileWatcher.kt         # Watch graph files
│   │   │   │   ├── FileImporter.kt        # Import Markdown/Org
│   │   │   │   └── GraphLoader.kt         # Load graph from disk
│   │   │   ├── parser/
│   │   │   │   ├── MLDocParser.kt         # Markdown parser
│   │   │   │   ├── OrgParser.kt           # Org-mode parser
│   │   │   │   └── BlockParser.kt         # Parse blocks
│   │   │   ├── theme/
│   │   │   │   ├── Theme.kt               # Theme definitions
│   │   │   │   ├── ThemeManager.kt        # Theme switching
│   │   │   │   └── Color.kt               # Color palette
│   │   │   ├── ui/
│   │   │   │   ├── App.kt                 # Main composable
│   │   │   │   ├── Navigation.kt          # Screen navigation
│   │   │   │   ├── AppState.kt            # UI state
│   │   │   │   ├── LogseqIcons.kt         # Custom icons
│   │   │   │   ├── components/            # Reusable components
│   │   │   │   ├── sidebar/               # Left/Right sidebar
│   │   │   │   ├── editor/                # Block editor
│   │   │   │   ├── page/                  # Page views
│   │   │   │   ├── block/                 # Block components
│   │   │   │   ├── search/                # Search UI
│   │   │   │   └── settings/              # Settings screen
│   │   │   └── shortcuts/
│   │   │       ├── ShortcutManager.kt
│   │   │       ├── ShortcutKeys.kt
│   │   │       └── KeyBinding.kt
│   │   ├── jvmTest/kotlin/com/logseq/     # Tests
│   │   └── commonMain/                     # Already exists
│   │       ├── kotlin/com/logseq/model/   # Domain models
│   │       ├── kotlin/com/logseq/repository/ # Repository interfaces
│   │       └── kotlin/com/logseq/usecase/ # Use cases
│   └── build.gradle.kts                    # Updated with Compose

## Project Structure

```
logseq-compose/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── kotlin/
│   │   │   │   └── com/logseq/
│   │   │   │       ├── Main.kt                    # App entry point
│   │   │   │       ├── LogseqApp.kt               # Application scope
│   │   │   │       ├── DesktopPlatform.kt         # Desktop-specific
│   │   │   │       ├── file/
│   │   │   │       │   ├── FileWatcher.kt         # Watch graph files
│   │   │   │       │   ├── FileImporter.kt        # Import Markdown/Org
│   │   │   │       │   └── GraphLoader.kt         # Load graph from disk
│   │   │   │       ├── parser/
│   │   │   │       │   ├── MLDocParser.kt         # Markdown parser
│   │   │   │       │   ├── OrgParser.kt           # Org-mode parser
│   │   │   │       │   └── BlockParser.kt         # Parse blocks
│   │   │   │       ├── theme/
│   │   │   │       │   ├── Theme.kt               # Theme definitions
│   │   │   │       │   ├── ThemeManager.kt        # Theme switching
│   │   │   │       │   └── Color.kt               # Color palette
│   │   │   │       ├── ui/
│   │   │   │       │   ├── App.kt                 # Main composable
│   │   │   │       │   ├── Navigation.kt          # Screen navigation
│   │   │   │       │   ├── AppState.kt            # UI state
│   │   │   │       │   ├── LogseqIcons.kt         # Custom icons
│   │   │   │       │   ├── components/
│   │   │   │       │   │   ├── Button.kt
│   │   │   │       │   │   ├── InputField.kt
│   │   │   │       │   │   ├── Menu.kt
│   │   │   │       │   │   ├── Dialog.kt
│   │   │   │       │   │   └── Modal.kt
│   │   │   │       │   ├── sidebar/
│   │   │   │       │   │   ├── LeftSidebar.kt
│   │   │   │       │   │   ├── RightSidebar.kt
│   │   │   │       │   │   ├── SidebarItem.kt
│   │   │   │       │   │   └── SidebarSection.kt
│   │   │   │       │   ├── editor/
│   │   │   │       │   │   ├── BlockEditor.kt
│   │   │   │       │   │   ├── EditorState.kt
│   │   │   │       │   │   ├── RichTextEditor.kt
│   │   │   │       │   │   ├── Selection.kt
│   │   │   │       │   │   └── Commands.kt
│   │   │   │       │   ├── page/
│   │   │   │       │   │   ├── PageList.kt
│   │   │   │       │   │   ├── PageCard.kt
│   │   │   │       │   │   ├── PageView.kt
│   │   │   │       │   │   └── PageMenu.kt
│   │   │   │       │   ├── block/
│   │   │   │       │   │   ├── BlockView.kt
│   │   │   │       │   │   ├── BlockTree.kt
│   │   │   │       │   │   ├── BlockMenu.kt
│   │   │   │       │   │   └── BlockDragDrop.kt
│   │   │   │       │   ├── search/
│   │   │   │       │   │   ├── SearchBar.kt
│   │   │   │       │   │   ├── SearchResults.kt
│   │   │   │       │   │   ├── SearchPopup.kt
│   │   │   │       │   │   └── CommandPalette.kt
│   │   │   │       │   └── settings/
│   │   │   │       │       ├── SettingsScreen.kt
│   │   │   │       │       ├── SettingsSections.kt
│   │   │   │       │       └── SettingsSearch.kt
│   │   │   │       └── shortcuts/
│   │   │   │           ├── ShortcutManager.kt
│   │   │   │           ├── ShortcutKeys.kt
│   │   │   │           └── KeyBinding.kt
│   │   │   └── resources/
│   │   │       ├── META-INF/
│   │   │       │   └── MANIFEST.MF
│   │   │       └── icons/
│   │   │           ├── app-icon.svg
│   │   │           └── toolbar-icons/
│   │   └── test/
│   │       └── kotlin/com/logseq/
│   │           ├── ParserTest.kt
│   │           ├── RepositoryTest.kt
│   │           └── UITest.kt
│   └── build.gradle.kts
├── db/                                          # Database module (already done)
│   └── ... (existing kmp module)
├── shared/                                      # Shared code for multiplatform
│   └── src/
│       └── commonMain/kotlin/com/logseq/
│           ├── model/                           # Domain models
│           ├── repository/                      # Repository interfaces
│           └── usecase/                         # Use cases
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

## Implementation Phases

### Phase 1: Foundation (Week 1-2)

#### 1.1 Project Setup
- [ ] Create Gradle project with Compose Desktop
- [ ] Set up SQLDelight with existing schema
- [ ] Configure build for native desktop packaging
- [ ] Set up CI/CD pipeline

#### 1.2 Application Shell
- [ ] Main window with menu bar
- [ ] Window controls (minimize, maximize, close)
- [ ] Theme integration (light/dark)
- [ ] Basic navigation structure

#### 1.3 Database Integration
- [ ] Initialize existing SQLDelight database
- [ ] Create repository layer
- [ ] Set up dependency injection
- [ ] Write basic CRUD tests

### Phase 2: Core Editor (Week 2-4)

#### 2.1 Block System
- [ ] Block data model
- [ ] Block creation/editing
- [ ] Block hierarchy (parent/child)
- [ ] Block moving and reordering

#### 2.2 Rich Text Editor
- [ ] Text input handling
- [ ] Basic formatting (bold, italic, strikethrough)
- [ ] Link creation
- [ ] Auto-complete for [[links]]

#### 2.3 Page System
- [ ] Page CRUD operations
- [ ] Page sidebar navigation
- [ ] Recent pages list
- [ ] Favorites

### Phase 3: Search & Commands (Week 4-5)

#### 3.1 Full-Text Search
- [ ] Integrate existing SearchRepository
- [ ] Search UI with results
- [ ] Search highlighting
- [ ] Recent searches

#### 3.2 Command Palette
- [ ] Quick command access (Ctrl+K)
- [ ] Navigate to pages
- [ ] Execute actions
- [ ] Custom commands

### Phase 4: Advanced Features (Week 5-8)

#### 4.1 Property System
- [ ] Property editor
- [ ] Property queries
- [ ] Advanced filtering

#### 4.2 File Management
- [ ] Graph folder selection
- [ ] File watching
- [ ] Import/Export
- [ ] Backup

#### 4.3 Settings
- [ ] Appearance settings
- [ ] Editor settings
- [ ] Shortcut customization
- [ ] Graph settings

### Phase 5: Polish (Week 8-10)

#### 5.1 Keyboard Shortcuts
- [ ] Global hotkeys
- [ ] Editor shortcuts
- [ ] Navigation shortcuts

#### 5.2 Performance
- [ ] Optimize rendering
- [ ] Lazy loading
- [ ] Caching

#### 5.3 Testing
- [ ] Unit tests
- [ ] Integration tests
- [ ] UI tests

## Core Architecture

### Dependency Injection (Manual)

```kotlin
// Simple DI without framework
object AppContainer {
    private val database = SteleDatabase.create()
    private val repositoryFactory = RepositoryFactoryImpl(database)
    
    val blockRepository: BlockRepository by lazy {
        repositoryFactory.createBlockRepository(GraphBackend.SQLDELIGHT)
    }
    
    val pageRepository: PageRepository by lazy {
        repositoryFactory.createPageRepository(GraphBackend.SQLDELIGHT)
    }
    
    val searchRepository: SearchRepository by lazy {
        repositoryFactory.createSearchRepository(GraphBackend.SQLDELIGHT)
    }
    
    val fileWatcher: FileWatcher by lazy {
        FileWatcher(graphPath)
    }
}
```

### Application State

```kotlin
data class AppState(
    val currentPage: Page? = null,
    val pages: List<Page> = emptyList(),
    val recentPages: List<Page> = emptyList(),
    val sidebarExpanded: Boolean = true,
    val rightSidebarTab: RightSidebarTab? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val theme: Theme = Theme.SYSTEM
)

enum class RightSidebarTab {
    TABLE_OF_CONTENTS,
    LINKED_REFERENCES,
    UNLINKED_REFERENCES
}
```

### UI State Management

```kotlin
class AppViewModel(
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    
    init {
        loadPages()
    }
    
    private fun loadPages() {
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            pageRepository.getAllPages().collect { result ->
                result.onSuccess { pages ->
                    _state.update { 
                        it.copy(pages = pages, isLoading = false) 
                    }
                }.onFailure { error ->
                    _state.update { 
                        it.copy(error = error.message, isLoading = false) 
                    }
                }
            }
        }
    }
    
    fun selectPage(page: Page) {
        _state.update { it.copy(currentPage = page) }
    }
}
```

### Navigation

```kotlin
sealed class Screen {
    data object Home : Screen()
    data object AllPages : Screen()
    data object Settings : Screen()
    data object Graph : Screen()
    data class Page(val pageId: String) : Screen()
    data class Search(val query: String = "") : Screen()
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val viewModel = remember { AppViewModel() }
    val state by viewModel.state.collectAsState()
    
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                state = state,
                onPageClick = { page ->
                    viewModel.selectPage(page)
                    navController.navigate(Screen.Page(page.uuid).route)
                },
                onSearchClick = {
                    navController.navigate(Screen.Search.route)
                }
            )
        }
        
        composable(Screen.Page.route) { backStackEntry ->
            val pageId = backStackEntry.arguments?.getString("pageId") ?: return@composable
            PageScreen(
                pageId = pageId,
                state = state,
                viewModel = viewModel
            )
        }
    }
}
```

## Keyboard Shortcuts

| Action | Shortcut |
|--------|----------|
| New Page | `Ctrl+N` |
| Search | `Ctrl+K` |
| Command Palette | `Ctrl+Shift+P` |
| Toggle Sidebar | `Ctrl+B` |
| Save | `Ctrl+S` |
| Undo | `Ctrl+Z` |
| Redo | `Ctrl+Shift+Z` |
| Bold | `Ctrl+B` |
| Italic | `Ctrl+I` |
| Jump to Page | `Ctrl+O` |
| Go Back | `Alt+Left` |
| Go Forward | `Alt+Right` |

## File Format Support

### Markdown Import
```markdown
# Page Title

- Block 1
  - Nested Block
- Block 2

## Section

Content with **bold** and *italic*
```

### Org-mode Import
```org
* Page Title
  :PROPERTIES:
  :CUSTOM_ID: page-id
  :END:
** Block 1
   - Nested Block
** Block 2
```

## Success Metrics

- [ ] App launches in < 2 seconds
- [ ] Open graph with 10k blocks in < 5 seconds
- [ ] Search returns results in < 100ms
- [ ] Editor responds immediately to typing
- [ ] Memory usage < 200MB for typical graph
- [ ] All unit tests pass
- [ ] Can import/export Markdown files
- [ ] Works without internet (offline-first)

## Build & Run

```bash
# Development
./gradlew :app:run

# Release build
./gradlew :app:package

# Run tests
./gradlew :app:test
```

## Packaging

```kotlin
// package-compose.gradle.kts
compose.desktop {
    application {
        mainClass = "com.logseq.MainKt"
        
        nativeDistributions {
            targetFormat(
                macOS = dmg,
                Windows = msi,
                Linux = deb
            )
            packageName = "logseq-compose"
            packageVersion = "0.1.0"
            
            macOS {
                bundleID = "com.logseq.app"
                iconFile.set(project.file("src/main/resources/icons/app-icon.icns"))
            }
            
            windows {
                iconFile.set(project.file("src/main/resources/icons/app-icon.ico"))
                menu = true
            }
            
            linux {
                iconFile.set(project.file("src/main/resources/icons/app-icon.png"))
            }
        }
    }
}
```

## References

- [Compose Desktop Documentation](https://www.jetbrains.com/compose/docs/)
- [SQLDelight Documentation](https://cashapp.github.io/sqldelight/)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
- [Material 3 Design](https://m3.material.io/)

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests and lint
5. Submit a PR

## License

MIT License - see LICENSE file
