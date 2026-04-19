#!/usr/bin/env bash
# SteleKit installer — works on any Linux distro (Arch, Debian, Fedora, etc.)
# Usage: bash install.sh [--version v0.1.0] [--prefix ~/.local]
set -euo pipefail

REPO="tstapler/stelekit"
VERSION=""
PREFIX="${HOME}/.local"

# Parse args
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --prefix)  PREFIX="$2";  shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

# Resolve latest version if not specified
if [[ -z "$VERSION" ]]; then
  VERSION=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" \
    | grep '"tag_name"' | head -1 | sed 's/.*"tag_name": *"\(.*\)".*/\1/')
fi

ARCH=$(uname -m)
APPIMAGE="SteleKit-${VERSION}-linux.AppImage"
URL="https://github.com/${REPO}/releases/download/${VERSION}/${APPIMAGE}"
BIN_DIR="${PREFIX}/bin"
SHARE_DIR="${PREFIX}/share"
INSTALL_PATH="${BIN_DIR}/stelekit"

echo "Installing SteleKit ${VERSION} to ${PREFIX} ..."

mkdir -p "${BIN_DIR}" "${SHARE_DIR}/applications" "${SHARE_DIR}/icons/hicolor/256x256/apps"

# Download AppImage
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "Downloading ${URL} ..."
curl -fL --progress-bar "${URL}" -o "${TMP}/${APPIMAGE}"
chmod +x "${TMP}/${APPIMAGE}"

# Extract icon from AppImage
pushd "$TMP" > /dev/null
APPIMAGE_EXTRACT_AND_RUN=1 "./${APPIMAGE}" --appimage-extract stelekit.png 2>/dev/null || true
popd > /dev/null

# Install AppImage
cp "${TMP}/${APPIMAGE}" "${INSTALL_PATH}"
chmod +x "${INSTALL_PATH}"
echo "Installed binary: ${INSTALL_PATH}"

# Install icon
if [[ -f "${TMP}/squashfs-root/stelekit.png" ]]; then
  cp "${TMP}/squashfs-root/stelekit.png" "${SHARE_DIR}/icons/hicolor/256x256/apps/stelekit.png"
  echo "Installed icon: ${SHARE_DIR}/icons/hicolor/256x256/apps/stelekit.png"
fi

# Install .desktop entry
cat > "${SHARE_DIR}/applications/stelekit.desktop" << DESKTOP
[Desktop Entry]
Name=SteleKit
Exec=${INSTALL_PATH}
Icon=stelekit
Type=Application
Categories=Office;
Comment=Markdown-based outliner and note-taking app
Terminal=false
StartupWMClass=stelekit
DESKTOP
echo "Installed desktop entry: ${SHARE_DIR}/applications/stelekit.desktop"

# Refresh caches
update-desktop-database "${SHARE_DIR}/applications" 2>/dev/null || true
gtk-update-icon-cache -f -t "${SHARE_DIR}/icons/hicolor" 2>/dev/null || true

echo ""
echo "SteleKit ${VERSION} installed successfully."
if [[ ":${PATH}:" != *":${BIN_DIR}:"* ]]; then
  echo "  Note: add ${BIN_DIR} to your PATH if it isn't already:"
  echo "    export PATH=\"\$PATH:${BIN_DIR}\""
fi
echo "  Run: stelekit"
echo "  Or launch from your application menu."
