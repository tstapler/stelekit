# ADR-002: Google Photos Import: System Photo Picker vs REST API

**Date**: 2026-05-16
**Status**: Accepted
**Deciders**: Tyler Stapler

## Context

The image-meter feature (US-5.1) requires importing photos from Google Photos into a SteleKit graph for annotation. The original design assumed programmatic browsing of the user's Google Photos library via the Google Photos Library API v1, using the `photoslibrary.readonly` OAuth scope.

On **March 31, 2025**, Google permanently removed the following OAuth scopes from the Photos Library API:
- `photoslibrary.readonly` — browse the entire photo library
- `photoslibrary.sharing` — shared album access
- `photoslibrary` — full library access

Any app requesting these scopes now receives `403 PERMISSION_DENIED`. Third-party applications can no longer programmatically read arbitrary photos from a user's Google Photos library. This is not a deprecation with a migration path — the scopes have been deleted.

The remaining available scopes are:
- `photoslibrary.appendonly` — upload media and create albums (still operational)
- `photoslibrary.edit.appcreateddata` — read/edit only items the app itself uploaded
- **Google Photos Picker API** — a system-level overlay UI where the user explicitly selects photos; the app receives access only to the selected items

## Decision

Use the **Android Photo Picker API** (system photo picker overlay) for all local photo selection, supplemented by the **Google Photos Picker API** (REST-based system overlay) for photos stored in Google Photos. Programmatic library browsing via the Photos Library API REST endpoint is not implemented.

The import flow becomes:
1. User taps "Import from Google Photos"
2. System Photos Picker or Google Photos Picker API launches as a system overlay
3. User selects one or more photos within the system UI
4. App receives URI(s) for only the selected items
5. App copies bytes to app-specific storage (`assets/images/`) immediately, as the URI grant is temporary
6. `ImageAnnotation` record and parent block are created

For write-back (US-5.4), only the `photoslibrary.appendonly` scope is requested, limiting write access to albums SteleKit itself created.

For cloud image import more broadly, **Google Drive** (unaffected by the Photos scope changes) is promoted as the primary cloud import path, with the Drive REST API v3 via Ktor providing full programmatic folder browsing.

## Alternatives Considered

**Photos Library API v1 with `photoslibrary.readonly` scope**
- Blocked unconditionally since March 31, 2025. Not a viable option regardless of implementation effort. Attempting to use this scope results in `403 PERMISSION_DENIED` for all users.

**Service Account with domain-wide delegation**
- Only applicable to Google Workspace accounts. Consumer Google accounts (the target user demographic for a note-taking app) cannot be accessed via service account delegation. Not applicable.

**Unofficial / undocumented API endpoints**
- Fragile, violates Google's Terms of Service, and subject to sudden breakage. Rejected.

**Deprioritize Google Photos import entirely; Google Drive only**
- A valid option given the scope restrictions. However, the Google Photos Picker API does provide a functional (if less convenient) import path for Photos-hosted images, so this capability is preserved with the picker flow rather than eliminated.

## Consequences

**Positive**
- Compliant with Google's current API policies; no risk of app suspension for ToS violation.
- The Android Photo Picker requires no `READ_MEDIA_IMAGES` permission on API 33+, reducing permission friction for users.
- The Google Photos Picker API provides a system-native UI that users recognize and trust for photo selection.
- `photoslibrary.appendonly` scope for write-back minimizes OAuth scope surface, reducing the likelihood of user rejection of the permission grant.

**Negative / Risks**
- The original US-5.1 user story (browse and import from Google Photos library by album, date, or search) cannot be implemented as designed. Users cannot browse their full library within the app; they must use the system picker overlay. Update US-5.1 acceptance criteria to reflect the picker-based flow.
- The returned picker URI is a **temporary grant** — the app must copy bytes to internal storage synchronously during the import transaction. If the app is backgrounded before copying completes, the URI may expire, causing a silent import failure. The import pipeline must complete the file copy before releasing the URI.
- No programmatic album listing: SteleKit cannot display "albums" from the user's Google Photos library — only the items the user actively selects in the picker.
- Google Drive is now the de-facto primary cloud browse-and-import path. UI copy and onboarding must set this expectation clearly.
- The Google Photos Picker API is a newer surface (launched 2023); its behavior across all Android OEM versions must be validated.
