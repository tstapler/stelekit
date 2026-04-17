# Task: Export/Import System Implementation

## Overview
Implement comprehensive export and import functionality that allows users to backup, share, and migrate their Logseq graphs in various formats while preserving all data and relationships.

## Current State
- No export/import functionality implemented in KMP
- ClojureScript has complete export system in `src/main/frontend/handler/export/`

## Implementation Tasks

### 1. **Export Core Engine**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/export/core/`

**Components**:
- `ExportEngine.kt` - Main export coordinator
- `ExportContext.kt` - Export state and configuration
- `ExportProcessor.kt - Base class for export processors`
- `ExportValidator.kt` - Validate export data and settings
- `ProgressTracker.kt` - Track export progress

**Features**:
- Progress reporting and cancellation
- Memory-efficient streaming for large graphs
- Error handling and recovery
- Concurrent export processing
- Export queue management

### 2. **HTML Export**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/export/html/`

**Components**:
- `HtmlExporter.kt` - Main HTML export processor
- `HtmlTemplateEngine.kt` - Template processing
- `HtmlAssetProcessor.kt` - Handle assets in HTML
- `HtmlThemeManager.kt` - Apply themes to HTML
- `HtmlNavigationBuilder.kt` - Build navigation structure

**Features**:
- Static site generation
- Custom theme support
- Responsive design
- Search functionality in exported HTML
- Mobile-friendly output
- SEO optimization

**Template Structure**:
```html
<!DOCTYPE html>
<html>
<head>
    <title>{{page.title}} - {{graph.name}}</title>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    {{#theme}}
    <link rel="stylesheet" href="{{theme.css}}">
    {{/theme}}
</head>
<body>
    <header>{{graph.name}}</header>
    <nav>{{navigation}}</nav>
    <main>{{content}}</main>
    <footer>{{footer}}</footer>
</body>
</html>
```

### 3. **Markdown Export**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/export/markdown/`

**Components**:
- `MarkdownExporter.kt` - Main markdown exporter
- `BlockConverter.kt` - Convert blocks to markdown
- `ReferenceProcessor.kt` - Handle page and block references
- `PropertyFormatter.kt` - Format properties in markdown
- `AssetLinker.kt` - Handle asset links

**Features**:
- Preserve block hierarchy
- Convert formatting to markdown syntax
- Handle embedded content
- Maintain metadata
- Support various markdown flavors

### 4. **OPML Export**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/export/opml/`

**Components**:
- `OpmlExporter.kt` - Main OPML exporter
- `OpmlBuilder.kt` - Build OPML XML structure
- `OpmlConverter.kt` - Convert Logseq data to OPML format
- `OpmlValidator.kt` - Validate OPML output

**Features**:
- Outline structure preservation
- Attribute support
- Compatibility with OPML readers
- Nested hierarchy handling

### 5. **JSON Export Formats**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/export/json/`

**Components**:
- `LogseqJsonExporter.kt` - Native Logseq format
- `RoamJsonExporter.kt` - Roam Research format
- `JsonSerializer.kt` - JSON serialization utilities
- `SchemaValidator.kt` - Validate JSON schemas
- `MigrationHelper.kt` - Format migration support

**Features**:
- Complete data preservation
- Relationship integrity
- Custom field support
- Version compatibility

### 6. **Archive Export (ZIP)**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/export/archive/`

**Components**:
- `ZipExporter.kt` - Create ZIP archives
- `ArchiveBuilder.kt` - Build archive structure
- `AssetArchiver.kt` - Include assets in archive
- `MetadataWriter.kt` - Write export metadata
- `CompressionManager.kt` - Optimize compression

**Features**:
- Complete graph export
- Asset bundling
- Metadata preservation
- Compression options
- Integrity verification

### 7. **Import System**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/import/`

**Components**:
- `ImportEngine.kt` - Main import coordinator
- `FormatDetector.kt` - Detect input format
- `ImportParser.kt` - Parse import data
- `DataMapper.kt` - Map to Logseq data model
- `ConflictResolver.kt` - Handle import conflicts

**Supported Formats**:
- Logseq JSON/ZIP exports
- Markdown files and folders
- OPML files
- Roam Research JSON
- Org-mode files
- Notion exports

### 8. **Import Processors**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/import/processors/`

**Components**:
- `MarkdownImporter.kt` - Import markdown files
- `OpmlImporter.kt` - Import OPML outlines
- `RoamImporter.kt` - Import Roam JSON
- `NotionImporter.kt` - Import Notion HTML/CSV
- `LogseqImporter.kt` - Import Logseq exports

**Features**:
- Format-specific parsing
- Data validation
- Error reporting
- Progress tracking
- Import preview

## Export Options Configuration

### Export Settings:
```kotlin
data class ExportSettings(
    val format: ExportFormat,
    val includePrivatePages: Boolean = false,
    val includeAssets: Boolean = true,
    val theme: String? = null,
    val outputDirectory: String,
    val compressionLevel: Int = 6,
    val preserveFileStructure: Boolean = true,
    val exportDate: String? = null,
    val customFields: Map<String, Any> = emptyMap()
)

enum class ExportFormat {
    HTML,
    MARKDOWN,
    OPML,
    LOGSEQ_JSON,
    ROAM_JSON,
    ZIP_ARCHIVE,
    PDF
}
```

## UI Components for Export/Import

### Export Interface:
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/export/ui/`

**Components**:
- `ExportDialog.kt` - Export configuration UI
- `FormatSelector.kt` - Choose export format
- `OptionsPanel.kt` - Configure export options
- `ProgressDialog.kt` - Show export progress
- `PreviewDialog.kt` - Preview export results

### Import Interface:
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/import/ui/`

**Components**:
- `ImportDialog.kt` - Import configuration UI
- `FileSelector.kt` - Select files to import
- `ConflictResolution.kt` - Handle import conflicts
- `MappingEditor.kt` - Map imported data
- `ImportPreview.kt` - Preview import results

## Integration Points

### With Repository Layer:
- Use `GraphRepository` for data access
- Integrate with block and page repositories
- Leverage caching for performance

### With UI System:
- Add export options to `Settings.kt`
- Integrate with `CommandPalette.kt` for quick export
- Update `Sidebar.kt` for import/export actions

### With Storage System:
- Use `PlatformFileSystem` for file operations
- Integrate with `GitManager` for version control
- Leverage asset management system

## Migration from ClojureScript

### Files to Reference:
- `src/main/frontend/handler/export/` - Complete export system
- `src/main/frontend/handler/export/html.cljs` - HTML export
- `src/main/frontend/handler/export/text.cljs` - Text/markdown export
- `src/main/frontend/handler/export/opml.cljs` - OPML export
- `src/main/frontend/handler/export/zip_helper.cljs` - ZIP utilities
- `src/main/frontend/handler/import.cljs` - Import system

### Key Functions to Port:
- `export-repo-as-html!` - HTML export
- `export-repo-as-zip!` - ZIP export
- `export-repo-as-opml!` - OPML export
- Import parsing functions
- Template processing logic

## Performance Considerations

### Large Graph Handling:
- Streaming processing for memory efficiency
- Chunked processing for large exports
- Progress reporting for long operations
- Cancellation support

### Resource Management:
- Memory-efficient asset handling
- Temporary file cleanup
- Compression optimization
- Concurrent processing where possible

## Testing Strategy

### Unit Tests:
- Test each export format independently
- Test import format detection
- Test data conversion accuracy
- Test error handling

### Integration Tests:
- Test end-to-end export/import cycles
- Test with large graphs
- Test asset handling
- Test conflict resolution

### Performance Tests:
- Test export speed for various graph sizes
- Test memory usage during export
- Test import processing speed
- Test compression efficiency

## Success Criteria

1. All export formats produce accurate output
2. Import preserves data integrity and relationships
3. Performance is acceptable for large graphs
4. UI is intuitive and provides good feedback
5. Error handling is comprehensive and helpful
6. All existing export functionality is preserved
7. New formats and options can be easily added

## Dependencies

### External Libraries:
- ZIP/archive libraries
- JSON processing libraries
- Template engines (for HTML)
- Markdown processing libraries
- XML processing (for OPML)

### Internal Dependencies:
- Complete repository layer
- File system abstraction
- Notification system
- Progress reporting infrastructure

## Security Considerations

### Export Security:
- Sanitize sensitive information when needed
- Validate user input paths
- Handle file permissions properly
- Check for malicious content in imports

### Import Security:
- Validate imported data
- Sanitize user-provided content
- Check for code injection
- Limit import size and complexity
