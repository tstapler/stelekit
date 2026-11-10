APK_SRC   := androidApp/build/outputs/apk/debug/androidApp-debug.apk
GDRIVE_REMOTE ?= gdrive
GDRIVE_FOLDER ?= SteleKit/APKs

GIT_HASH  := $(shell git rev-parse --short HEAD)
APK_NAME  := stelekit-$(GIT_HASH)-debug.apk

.PHONY: build-android upload-android ready private-string-guard install-private-string-hook

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

# Local gate: fails closed (exit 2) when the private denylist is missing.
# Generate it once with: python3 scripts/home-sketch/build-denylist.py
private-string-guard:
	python3 scripts/ci/private-string-guard.py --require-denylist --diff-base $(or $(BASE),main)

ready: install-private-string-hook private-string-guard

# Installs the pre-commit hook; refuses to overwrite an existing, different pre-commit hook.
install-private-string-hook:
	@hook="$$(git rev-parse --git-path hooks)/pre-commit"; \
	if [ -e "$$hook" ] && ! cmp -s "$$hook" scripts/git-hooks/private-string-guard; then \
		echo "$$hook exists and differs; add scripts/git-hooks/private-string-guard to it by hand"; exit 1; \
	fi; \
	install -m 0755 scripts/git-hooks/private-string-guard "$$hook" && echo "installed $$hook"
