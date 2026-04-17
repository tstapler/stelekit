# Migration Plan: Flashcards / SRS

## 1. Discovery & Requirements
Flashcards allow users to review knowledge using Spaced Repetition Systems (SRS).

### Existing Artifacts
- `src/main/frontend/extensions/srs`: Core SRS logic.
- `src/main/frontend/extensions/fsrs.cljs`: Implementation of the FSRS algorithm.

### Functional Requirements
- **Card Creation**: Tag blocks with `#card`.
- **Review Queue**: Calculate which cards are due today.
- **Algorithm**: Support SM-2 (Anki default) and FSRS (Free Spaced Repetition Scheduler).
- **UI**: Question/Answer interface with "Again", "Hard", "Good", "Easy" buttons.

### Non-Functional Requirements
- **Correctness**: The scheduling algorithm must be mathematically correct.
- **Sync**: Review logs must sync across devices.

## 2. Architecture & Design (KMP)

### Logic Layer (Common)
- **SrsEngine**: Interface for scheduling algorithms.
- **FsrsAlgorithm**: Pure Kotlin implementation of FSRS.
- **CardRepository**: Query DB for blocks with `#card` tag.
- **ReviewLog**: Store history of reviews.

### UI Layer (Compose Multiplatform)
- **Component**: `FlashcardScreen`.
- **Component**: `CardFace` (Front/Back rendering).

## 3. Proactive Bug Identification (Known Issues)

### 🐛 Logic: Timezone "Next Day" [SEVERITY: Medium]
- **Description**: If a user reviews a card at 11 PM and then travels to a timezone where it's 1 AM, the "Next Day" calculation might be off.
- **Mitigation**: Store review timestamps in UTC. Calculate "Start of Day" based on user's current local time setting.

### 🐛 Data: Card State Conflicts [SEVERITY: Low]
- **Description**: Reviewing the same card on two devices offline leads to conflict.
- **Mitigation**: Merge strategy: "Max Repetitions" or "Latest Review". Usually, taking the latest review is sufficient.

## 4. Implementation Roadmap

### Phase 1: Algorithm Port
- [ ] Port FSRS logic to Kotlin (or use an existing Kotlin/Java library).
- [ ] Unit Test: Verify scheduling intervals against known FSRS test vectors.

### Phase 2: Query Logic
- [ ] Implement SQL query to find all `#card` blocks.
- [ ] Implement "Due" filtering logic.

### Phase 3: UI Implementation
- [ ] Build the Flashcard Review UI in Compose.
- [ ] Connect UI buttons to the Algorithm.

## 5. Migration Checklist
- [ ] **Logic**: FSRS algorithm passes unit tests.
- [ ] **Logic**: Cards are correctly identified from graph.
- [ ] **UI**: Flashcard UI works (flip animation).
- [ ] **Parity**: Review history is preserved.

