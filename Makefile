APK_SRC   := androidApp/build/outputs/apk/debug/androidApp-debug.apk
GDRIVE_REMOTE ?= gdrive
GDRIVE_FOLDER ?= SteleKit/APKs

GIT_HASH  := $(shell git rev-parse --short HEAD)
APK_NAME  := stelekit-$(GIT_HASH)-debug.apk

.PHONY: build-android upload-android

build-android:
	./gradlew :androidApp:assembleDebug

upload-android: build-android
	@if ! command -v rclone >/dev/null 2>&1; then \
		echo "rclone not found, installing via brew..."; \
		brew install rclone; \
	fi
	@if ! rclone listremotes | grep -q "^$(GDRIVE_REMOTE):"; then \
		echo ""; \
		echo "No rclone remote named '$(GDRIVE_REMOTE)' found."; \
		echo "Run: rclone config"; \
		echo "Add a Google Drive remote and name it '$(GDRIVE_REMOTE)', then retry."; \
		exit 1; \
	fi
	rclone copyto $(APK_SRC) $(GDRIVE_REMOTE):$(GDRIVE_FOLDER)/$(APK_NAME) --progress
	@echo "Uploaded to Google Drive: $(GDRIVE_REMOTE):$(GDRIVE_FOLDER)/$(APK_NAME)"
