# Task: Flashcards & Spaced Repetition System Implementation

## Overview
Implement a comprehensive flashcard system with spaced repetition learning algorithm that allows users to create flashcards from blocks and review them using optimized scheduling.

## Current State
- No flashcard functionality implemented in KMP
- ClojureScript has FSRS-based system in `src/main/frontend/extensions/fsrs.cljs`

## Implementation Tasks

### 1. **FSRS Algorithm Implementation**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/flashcards/fsrs/`

**Components**:
- `FsrsAlgorithm.kt` - Core FSRS implementation
- `FsrsCard.kt` - Card data model
- `FsrsReview.kt` - Review session management
- `FsrsScheduler.kt` - Review scheduling
- `FsrsAnalytics.kt` - Learning analytics

**Features**:
- Implementation of FSRS-5 algorithm
- Difficulty adjustment
- Interval calculation
- Retrievability estimation
- Learning curve optimization

### 2. **Card Creation & Management**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/flashcards/cards/`

**Components**:
- `CardCreator.kt` - Create cards from blocks
- `CardParser.kt` - Parse card formats
- `CardManager.kt` - Manage card lifecycle
- `CardValidator.kt` - Validate card content
- `CardTransformer.kt` - Transform blocks to cards

**Card Types**:
```kotlin
sealed class Flashcard {
    abstract val id: String
    abstract val front: String
    abstract val back: String
    abstract val source: BlockRef
    abstract val tags: List<String>
    abstract val created: LocalDateTime
}

data class BasicCard(
    override val id: String,
    override val front: String,
    override val back: String,
    override val source: BlockRef,
    override val tags: List<String>,
    override val created: LocalDateTime
) : Flashcard()

data class ClozeCard(
    override val id: String,
    val text: String,
    val clozes: List<ClozeDeletion>,
    override val source: BlockRef,
    override val tags: List<String>,
    override val created: LocalDateTime
) : Flashcard() {
    override val front: String = text
    override val back: String = "Hidden for cloze review"
}
```

### 3. **Review Interface**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/flashcards/ui/`

**Components**:
- `ReviewSession.kt` - Main review interface
- `CardViewer.kt` - Display individual cards
- `AnswerButtons.kt` - Rating/feedback buttons
- `ReviewControls.kt` - Session controls
- `ReviewStats.kt` - Review statistics display

**Features**:
- Clean, distraction-free interface
- Keyboard shortcuts for quick rating
- Touch-friendly mobile interface
- Progress indicators
- Session customization

### 4. **Study Queue Management**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/flashcards/queue/`

**Components**:
- `StudyQueue.kt` - Manage review queues
- `QueueBuilder.kt` - Build daily review queues
- `PriorityManager.kt` - Prioritize cards
- `LoadBalancer.kt` - Balance daily workload
- `QueueOptimizer.kt` - Optimize review order

**Features**:
- Daily new card limits
- Review due cards
- Overdue card management
- Tag-based filtering
- Difficulty-based prioritization

### 5. **Learning Analytics**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/flashcards/analytics/`

**Components**:
- `LearningStats.kt` - Overall learning statistics
- `ProgressTracker.kt` - Track learning progress
- `RetentionAnalyzer.kt` - Analyze retention rates
- `PerformanceMetrics.kt` - Performance indicators
- `ReportGenerator.kt` - Generate learning reports

**Metrics**:
- Cards learned per day
- Retention rates
- Difficulty distribution
- Review streaks
- Learning efficiency
- Card performance by tag

### 6. **Card Formats & Templates**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/flashcards/templates/`

**Components**:
- `CardTemplate.kt` - Base template system
- `BasicTemplate.kt` - Basic front/back cards
- `ClozeTemplate.kt` - Cloze deletion cards
- `ImageCardTemplate.kt` - Image-based cards
- `AudioCardTemplate.kt` - Audio-based cards

**Template Syntax**:
```markdown
# Basic Card
Front: What is the capital of France?
Back: Paris

# Cloze Card
The capital of {{c1::France}} is {{c2::Paris}}.

# Image Card
Front: [![Image Caption](path/to/image.jpg)]
Back: Answer or explanation

# Reversed Card
Front -> Back: What is X?
Back -> Front: X is...
```

### 7. **Spaced Repetition Settings**
**Files to Create**: `kmp/src/commonMain/kotlin/com/logseq/kmp/flashcards/settings/`

**Components**:
- `FsrsSettings.kt` - FSRS algorithm settings
- `ReviewSettings.kt` - Review session settings
- `CardSettings.kt` - Card creation settings
- `LearningGoals.kt` - Learning goals management
- `SettingsPersistence.kt` - Settings storage

**Configurable Options**:
- Daily new card limit
- Maximum reviews per day
- Card scheduling parameters
- Learning goals
- Review reminders

## Integration Points

### With Repository Layer:
- Store card data in database
- Track review history
- Cache learning statistics
- Persist user settings

### With UI System:
- Add flashcards to `Sidebar.kt` navigation
- Integrate with `CommandPalette.kt`
- Update `Settings.kt` for flashcard options
- Add to `PageView.kt` context menus

### With Block System:
- Create cards from block content
- Link cards back to source blocks
- Support various block formats
- Handle block content changes

## Migration from ClojureScript

### Files to Reference:
- `src/main/frontend/extensions/fsrs.cljs` - FSRS implementation
- `src/main/frontend/components/left_sidebar.cljs` (flashcard navigation)
- `src/main/frontend/components/content.cljs` (flashcard context menus)
- `src/main/frontend/state.cljs` (flashcard state management)

### Key Functions to Port:
- FSRS algorithm implementation
- Card creation logic
- Review session management
- Learning statistics calculation

## Flashcard Creation Workflow

### From Block Content:
1. Select block(s) to convert to cards
2. Choose card type (Basic, Cloze, etc.)
3. Customize card format
4. Add tags and scheduling
5. Preview and save cards

### Automatic Creation:
- Detect flashcard patterns in blocks
- Auto-generate cards from Q&A format
- Create cards from cloze deletions
- Support bulk creation

## Review Session Flow

### Daily Review:
1. Load due cards based on schedule
2. Present cards in optimal order
3. Collect user ratings (Again, Hard, Good, Easy)
4. Update card intervals and difficulty
5. Track review statistics

### Adaptive Learning:
- Adjust difficulty based on performance
- Optimize review intervals
- Balance new vs review cards
- Adapt to user learning patterns

## Testing Strategy

### Unit Tests:
- Test FSRS algorithm accuracy
- Test card creation logic
- Test scheduling calculations
- Test statistical calculations

### Integration Tests:
- Test end-to-end review sessions
- Test card-block synchronization
- Test settings persistence
- Test performance with large card sets

### Learning Tests:
- Test learning effectiveness
- Test retention rates
- Test algorithm parameters
- Test user experience studies

## UI Components Design

### Review Screen Layout:
```
┌─────────────────────────────────┐
│ Progress: 15/100 cards          │
│ ⏱️ Time: 5:23                   │
├─────────────────────────────────┤
│                                 │
│        Card Front Content        │
│                                 │
│                         [Show] │
├─────────────────────────────────┤
│ [Again] [Hard] [Good] [Easy]   │
└─────────────────────────────────┘
```

### Flashcard Management UI:
- Card browser with search and filters
- Batch operations on cards
- Statistics dashboard
- Settings and preferences
- Export/import functionality

## Success Criteria

1. FSRS algorithm produces optimal scheduling
2. Card creation is intuitive and flexible
3. Review interface is clean and efficient
4. Learning analytics provide meaningful insights
5. Performance is good with large card collections
6. Mobile experience is touch-friendly
7. Integration with blocks is seamless

## Dependencies

### External Libraries:
- Statistical calculation libraries
- Date/time utilities
- Data visualization for analytics
- Audio playback support (if needed)

### Internal Dependencies:
- Repository layer for persistence
- Block system for card creation
- UI component library
- Settings management system

## Platform Considerations

### Desktop:
- Full keyboard shortcut support
- Multiple monitor support
- Rich formatting support
- High-performance rendering

### Mobile:
- Touch gestures for card navigation
- Swipe for rating cards
- Offline review capability
- Optimized for small screens

### Sync Considerations:
- Cross-device review progress sync
- Cloud backup for card data
- Conflict resolution for simultaneous reviews
- Offline-first architecture

## Future Extensions

### Advanced Features:
- Image occlusion cards
- Audio pronunciation cards
- Spaced repetition for images
- Collaborative card creation
- AI-powered card generation
- Adaptive learning algorithms
- Multi-modal learning support
