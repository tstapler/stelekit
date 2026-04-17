# Task: PDF Integration System Implementation

## Overview
Implement comprehensive PDF viewing, annotation, and integration functionality that allows users to read, annotate, and reference PDFs within their Logseq graphs.

## Current State
- KMP has basic `PDFViewer.kt` placeholder
- ClojureScript has extensive PDF system in `src/main/frontend/extensions/pdf/`

## Implementation Tasks

### 1. **PDF Rendering Engine**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/pdf/engine/`

**Components**:
- `PdfRenderer.kt` - Core PDF rendering interface
- `PdfPageRenderer.kt` - Render individual pages
- `PdfDocument.kt` - PDF document management
- `PdfViewport.kt` - Viewport and zoom management
- `PdfCache.kt` - Cache rendered pages

**Platform-Specific Implementations**:
- `PdfRendererDesktop.kt` - PDF.js on desktop (via WebView)
- `PdfRendererWeb.kt` - PDF.js in browser
- `PdfRendererAndroid.kt` - Android PDF library
- `PdfRendererIOS.kt` - iOS PDFKit integration

**Features**:
- High-quality rendering at various zoom levels
- Smooth scrolling and pagination
- Memory-efficient page caching
- Support for large PDF files
- Progressive loading

### 2. **PDF Annotation System**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/pdf/annotations/`

**Components**:
- `AnnotationManager.kt` - Manage all annotations
- `HighlightAnnotation.kt` - Text highlight annotations
- `NoteAnnotation.kt` - Sticky note annotations
- `DrawingAnnotation.kt` - Free-form drawing
- `AreaAnnotation.kt` - Area/rectangle annotations

**Features**:
- Text highlighting with colors
- Sticky notes with rich text
- Free-form drawing and shapes
- Underline and strikethrough
- Annotation search and filtering

### 3. **PDF Navigation & Controls**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/pdf/navigation/`

**Components**:
- `PdfNavigator.kt` - Navigation and controls
- `PdfSearchEngine.kt` - Text search within PDFs
- `PdfTableOfContents.kt` - Navigate using TOC
- `PdfBookmarks.kt` - Custom bookmarks
- `PdfHistory.kt` - Navigation history

**Features**:
- Page navigation with thumbnails
- Full-text search with highlighting
- Outline/table of contents navigation
- Custom bookmarks
- Go to page/section
- Navigation history

### 4. **PDF Asset Management**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/pdf/assets/`

**Components**:
- `PdfAssetManager.kt` - Manage PDF assets
- `PdfImporter.kt` - Import PDF files
- `PdfStorage.kt` - PDF file storage
- `PdfMetadata.kt` - Extract and store metadata
- `PdfOrganizer.kt` - Organize PDF collection

**Features**:
- PDF file import and organization
- Automatic metadata extraction
- PDF linking and referencing
- Asset optimization and compression
- Version control integration

### 5. **PDF-Block Integration**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/pdf/integration/`

**Components**:
- `PdfBlockCreator.kt` - Create blocks from PDF content
- `PdfReferenceManager.kt` - Handle PDF references
- `PdfLinkProcessor.kt` - Process PDF links
- `PdfQuoteExtractor.kt` - Extract quotes for blocks
- `PdfCitationManager.kt` - Manage citations

**Features**:
- Link PDFs to blocks and pages
- Create quoted blocks from PDF text
- Automatic citation generation
- PDF preview in block references
- Cross-reference PDF annotations

### 6. **PDF UI Components**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/pdf/ui/`

**Components**:
- `PdfViewer.kt` - Main PDF viewing component
- `PdfToolbar.kt` - Viewer controls and tools
- `PdfSidebar.kt` - Navigation and search sidebar
- `PdfAnnotationToolbar.kt` - Annotation tools
- `PdfSettings.kt` - PDF viewing preferences

**Features**:
- Clean, responsive UI
- Keyboard shortcuts
- Touch gestures for mobile
- Full-screen mode
- Split view with Logseq content

## PDF Data Models

### Annotation Types:
```kotlin
sealed class PdfAnnotation {
    abstract val id: String
    abstract val page: Int
    abstract val color: String
    abstract val created: LocalDateTime
    abstract val modified: LocalDateTime
}

data class HighlightAnnotation(
    override val id: String,
    override val page: Int,
    val selectedText: String,
    val bbox: RectangleF,
    override val color: String,
    override val created: LocalDateTime,
    override val modified: LocalDateTime,
    val note: String? = null
) : PdfAnnotation()

data class NoteAnnotation(
    override val id: String,
    override val page: Int,
    val position: PointF,
    val content: String,
    override val color: String,
    override val created: LocalDateTime,
    override val modified: LocalDateTime
) : PdfAnnotation()
```

## Integration Points

### With Repository Layer:
- Store annotations in database
- Link PDFs to blocks and pages
- Cache metadata for quick access
- Search integration

### With UI System:
- Integrate with `PageView.kt` for PDF references
- Add PDF controls to `TopBar.kt`
- Include in `CommandPalette.kt` search
- Update `Sidebar.kt` for PDF navigation

### With Asset System:
- Use existing asset management
- Leverage file system abstraction
- Integrate with `GitManager` for versioning

## Migration from ClojureScript

### Files to Reference:
- `src/main/frontend/extensions/pdf/utils.cljs` - PDF utilities
- `src/main/frontend/extensions/pdf/toolbar.cljs` - PDF toolbar
- `src/main/frontend/extensions/pdf/windows.cljs` - PDF window management
- `src/main/frontend/extensions/pdf/assets.cljs` - PDF assets
- `src/main/frontend/handler/editor.cljs` (PDF-related parts)

### Key Functions to Port:
- PDF rendering and display
- Annotation creation and management
- PDF search functionality
- PDF navigation controls
- PDF linking and referencing

## Performance Considerations

### Large PDF Handling:
- Lazy page rendering
- Memory-efficient caching
- Progressive loading
- Background preprocessing

### Memory Management:
- Page cache size limits
- Annotation optimization
- Garbage collection for large files
- Streaming for network-based PDFs

## Testing Strategy

### Unit Tests:
- Test PDF rendering quality
- Test annotation operations
- Test search accuracy
- Test navigation functions

### Integration Tests:
- Test PDF-block integration
- Test with various PDF formats
- Test annotation persistence
- Test performance with large files

### UI Tests:
- Test viewer interactions
- Test toolbar controls
- Test annotation tools
- Test mobile gestures

## PDF Reference Format

### Link Syntax:
```markdown
# PDF Page Reference
![Page 23](assets/my-document.pdf#page=23)

# PDF Highlight Reference
> "Important quote from PDF" [[assets/my-document.pdf#highlight=abc123]]

# PDF Annotation Reference
[[Note about this section -> assets/my-document.pdf#note=def456]]
```

## Platform Considerations

### Desktop:
- Full PDF.js integration
- Native PDF viewer fallback
- External PDF application support
- System integration

### Mobile:
- Touch-optimized controls
- Zoom gestures
- Split-screen viewing
- Offline PDF caching

### Web:
- Browser PDF viewer integration
- Web Workers for processing
- Download streaming
- Progressive enhancement

## Success Criteria

1. Users can view PDFs within Logseq
2. Annotation system is comprehensive and intuitive
3. PDF references integrate seamlessly with blocks
4. Performance is good for large PDF files
5. UI is responsive and touch-friendly on mobile
6. Search within PDFs works accurately
7. PDF organization and management is efficient

## Dependencies

### External Libraries:
- PDF.js or equivalent
- PDF parsing libraries
- Annotation storage libraries
- Image processing for thumbnails
- Text extraction libraries

### Platform-Specific:

#### Desktop:
- WebView integration
- Native PDF viewers
- File system access

#### Mobile:
- Native PDF frameworks
- Touch gesture libraries
- Mobile storage optimization

#### Web:
- Web Workers
- Blob handling
- Progressive loading

## Security Considerations

### PDF Security:
- Validate PDF files before processing
- Handle malicious PDFs safely
- Secure annotation storage
- Prevent code execution from PDFs

### Privacy:
- Secure local storage for annotations
- Handle sensitive PDF content appropriately
- Respect user privacy for personal documents
- Secure cloud synchronization if implemented
