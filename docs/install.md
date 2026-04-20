## Install

**Homebrew (macOS and Linux):**
```bash
brew tap tstapler/stelekit https://github.com/tstapler/stelekit
brew install tstapler/stelekit/stelekit
```

**Linux AppImage** (works on any distro — recommended if unsure):
```bash
chmod +x SteleKit-*.AppImage
./SteleKit-*.AppImage
```

If the app fails to start, try `APPIMAGE_EXTRACT_AND_RUN=1 ./SteleKit-*.AppImage`.

**Debian/Ubuntu:** `sudo dpkg -i SteleKit-*.deb`

**Fedora/openSUSE:** `sudo rpm -i SteleKit-*.rpm`

**Windows:** run `SteleKit-*.msi` from the [latest release](https://github.com/tstapler/stelekit/releases/latest).

**Android — F-Droid** (recommended — automatic updates):
1. Install [F-Droid](https://f-droid.org/)
2. Settings → Repositories → **+** → enter:
   ```
   https://tstapler.github.io/stelekit/fdroid/repo
   ```
3. Search for **SteleKit** and install.

**Android — direct APK:** download `SteleKit-*-android.apk` from the [latest release](https://github.com/tstapler/stelekit/releases/latest). Enable *Install from unknown sources* first.
