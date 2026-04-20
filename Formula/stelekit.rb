# Formula managed by the release workflow — do not edit SHA256 manually.
class Stelekit < Formula
  desc "Markdown-based outliner and note-taking app (Kotlin Multiplatform)"
  homepage "https://github.com/tstapler/stelekit"
  version "0.5.0"

  on_linux do
    url "https://github.com/tstapler/stelekit/releases/download/v#{version}/SteleKit-v#{version}-linux.AppImage"
    sha256 "494fc7c27baf7b3afb2524ceb53dc186e8dec745547020891144d5a73b8faf3e"
  end

  on_macos do
    url "https://github.com/tstapler/stelekit/releases/download/v#{version}/SteleKit-v#{version}-macos.dmg"
    sha256 "eb61f10add384e4816c441c7a1893d319ac205d5f45460293f977df6899b55a2"
  end

  def install
    if OS.linux?
      appimage = "SteleKit-v#{version}-linux.AppImage"
      (prefix/"bin").mkpath
      cp appimage, prefix/"bin/stelekit"
      chmod 0755, prefix/"bin/stelekit"

      # Desktop entry
      (share/"applications").mkpath
      (share/"applications/stelekit.desktop").write <<~DESKTOP
        [Desktop Entry]
        Name=SteleKit
        Exec=#{HOMEBREW_PREFIX}/bin/stelekit
        Icon=stelekit
        Type=Application
        Categories=Office;
        Comment=Markdown-based outliner and note-taking app
        Terminal=false
        StartupWMClass=stelekit
      DESKTOP

      # Extract icon from AppImage and install to hicolor
      system "#{prefix}/bin/stelekit", "--appimage-extract", "stelekit.png"
      icon_dir = share/"icons/hicolor/256x256/apps"
      icon_dir.mkpath
      cp "squashfs-root/stelekit.png", icon_dir/"stelekit.png"
      rm_rf "squashfs-root"

    elsif OS.mac?
      system "hdiutil", "attach", "-quiet", "-nobrowse", "SteleKit-v#{version}-macos.dmg"
      cp_r "/Volumes/stelekit/stelekit.app", prefix
      system "hdiutil", "detach", "-quiet", "/Volumes/stelekit"
      bin.write_exec_script "#{prefix}/stelekit.app/Contents/MacOS/stelekit"
    end
  end

  def post_install
    if OS.linux?
      system "update-desktop-database", share/"applications" rescue nil
      system "gtk-update-icon-cache", "-f", "-t", share/"icons/hicolor" rescue nil
    end
  end

  def caveats
    if OS.linux?
      <<~EOS
        SteleKit is installed as an AppImage. To appear in your app launcher
        immediately, refresh the desktop database:
          update-desktop-database ~/.local/share/applications
        If the app fails to start, try:
          APPIMAGE_EXTRACT_AND_RUN=1 stelekit
      EOS
    end
  end

  test do
    assert_predicate bin/"stelekit", :executable?
  end
end
