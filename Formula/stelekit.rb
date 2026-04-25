# Formula managed by the release workflow — do not edit SHA256 manually.
class Stelekit < Formula
  desc "Markdown-based outliner and note-taking app (Kotlin Multiplatform)"
  homepage "https://github.com/tstapler/stelekit"
  version "0.9.5"

  on_linux do
    url "https://github.com/tstapler/stelekit/releases/download/v#{version}/SteleKit-v#{version}-linux.AppImage"
    sha256 "844a653944034530d8a99bf01260311d63c1ea22f745cd799723d03506731216"
  end

  on_macos do
    url "https://github.com/tstapler/stelekit/releases/download/v#{version}/SteleKit-v#{version}-macos.dmg"
    sha256 "d3e367511672df9b655f5a8e002993c80b2ad28a977843b720683d7b734fe006"

    depends_on "openjdk"
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
      cp_r "stelekit.app", prefix
      # Replace the bundled JVM with Homebrew's openjdk for macOS compatibility.
      # The Compose Desktop native launcher looks for the JVM at Contents/runtime/Contents/Home.
      bundled_jvm = prefix/"stelekit.app/Contents/runtime/Contents/Home"
      bundled_jvm.rmtree if bundled_jvm.exist?
      bundled_jvm.make_relative_symlink Formula["openjdk"].opt_libexec/"openjdk.jdk/Contents/Home"
      bin.write_exec_script prefix/"stelekit.app/Contents/MacOS/stelekit"
    end
  end

  def post_install
    if OS.linux?
      system "update-desktop-database", share/"applications" rescue nil
      system "gtk-update-icon-cache", "-f", "-t", share/"icons/hicolor" rescue nil

      # Copy desktop entry and icon to the user's local share so app launchers
      # that don't check HOMEBREW_PREFIX (i.e. XDG_DATA_DIRS not set) find them.
      home = Pathname.new(ENV.fetch("HOME", ""))
      user_apps = home/".local/share/applications"
      user_apps.mkpath
      cp share/"applications/stelekit.desktop", user_apps/"stelekit.desktop"
      system "update-desktop-database", user_apps rescue nil

      user_icons = home/".local/share/icons/hicolor/256x256/apps"
      user_icons.mkpath
      cp share/"icons/hicolor/256x256/apps/stelekit.png", user_icons/"stelekit.png"
    end
  end

  def caveats
    if OS.linux?
      <<~EOS
        SteleKit has been registered in your app launcher automatically.
        If it doesn't appear, your desktop session may need to reload. Log out
        and back in, or run:
          update-desktop-database ~/.local/share/applications

        For app launchers to also pick up future Homebrew-installed apps, add
        Homebrew's share directory to XDG_DATA_DIRS by sourcing brew shellenv
        in ~/.profile or ~/.bash_profile:
          eval "$(/home/linuxbrew/.linuxbrew/bin/brew shellenv)"

        If the app fails to start, try:
          APPIMAGE_EXTRACT_AND_RUN=1 stelekit
      EOS
    end
  end

  test do
    assert_predicate bin/"stelekit", :executable?
  end
end
