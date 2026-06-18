# Formula managed by the release workflow — do not edit SHA256 manually.
class Stelekit < Formula
  desc "Markdown-based outliner and note-taking app (Kotlin Multiplatform)"
  homepage "https://github.com/tstapler/stelekit"
  version "0.49.2"

  on_linux do
    url "https://github.com/tstapler/stelekit/releases/download/v#{version}/SteleKit-v#{version}-linux.AppImage"
    sha256 "f0d52d2af228c0c3dce96bf46ab5444cede717c8a50eecd42d09677c085e7aff"
  end

  on_macos do
    url "https://github.com/tstapler/stelekit/releases/download/v#{version}/SteleKit-v#{version}-macos.dmg"
    sha256 "ac3acb693927b963262a1ceeaebf37c09534d5ebcd5609e7bc7343ee3c1750b1"
  end

  def install
    if OS.linux?
      appimage = "SteleKit-v#{version}-linux.AppImage"
      (libexec).mkpath
      cp appimage, libexec/"stelekit.AppImage"
      chmod 0755, libexec/"stelekit.AppImage"

      # Wrapper script — sets APPIMAGE_EXTRACT_AND_RUN=1 so AppImage works without FUSE
      (bin/"stelekit").write <<~SH
        #!/bin/sh
        exec env APPIMAGE_EXTRACT_AND_RUN=1 "#{libexec}/stelekit.AppImage" "$@"
      SH
      chmod 0755, bin/"stelekit"

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
      system "env", "APPIMAGE_EXTRACT_AND_RUN=1", "#{libexec}/stelekit.AppImage", "--appimage-extract", "stelekit.png"
      icon_dir = share/"icons/hicolor/256x256/apps"
      icon_dir.mkpath
      cp "squashfs-root/stelekit.png", icon_dir/"stelekit.png"
      rm_rf "squashfs-root"

    elsif OS.mac?
      cp_r "stelekit.app", prefix
      bin.write_exec_script prefix/"stelekit.app/Contents/MacOS/stelekit"
    end
  end

  def post_install
    if OS.mac?
      apps_dir = Pathname.new(ENV.fetch("HOME", ""))/"Applications"
      apps_dir.mkpath
      app_symlink = apps_dir/"SteleKit.app"
      app_symlink.delete if app_symlink.symlink? || app_symlink.exist?
      app_symlink.make_relative_symlink prefix/"stelekit.app"
    elsif OS.linux?
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
    if OS.mac?
      <<~EOS
        SteleKit has been added to ~/Applications so it appears in Finder and Launchpad.
        You can also launch it from the command line:
          stelekit
      EOS
    elsif OS.linux?
      <<~EOS
        SteleKit has been registered in your app launcher automatically.
        If it doesn't appear, your desktop session may need to reload. Log out
        and back in, or run:
          update-desktop-database ~/.local/share/applications

        For app launchers to also pick up future Homebrew-installed apps, add
        Homebrew's share directory to XDG_DATA_DIRS by sourcing brew shellenv
        in ~/.profile or ~/.bash_profile:
          eval "$(/home/linuxbrew/.linuxbrew/bin/brew shellenv)"
      EOS
    end
  end

  test do
    assert_predicate bin/"stelekit", :executable?
  end
end
