# Formula managed by the release workflow — do not edit SHA256 manually.
class Stelekit < Formula
  desc "Markdown-based outliner and note-taking app (Kotlin Multiplatform)"
  homepage "https://github.com/tstapler/stelekit"
  version "0.60.0"

  on_linux do
    url "https://github.com/tstapler/stelekit/releases/download/v#{version}/SteleKit-v#{version}-linux.AppImage"
    sha256 "e5641f010cb182b52effc7a8fa41f17d9f8e2207ae8b0b0d0691aa96bd925c49"
  end

  on_macos do
    url "https://github.com/tstapler/stelekit/releases/download/v#{version}/SteleKit-v#{version}-macos.dmg"
    sha256 "50f20601afb6a0e0ed3b9c7b8669481f636f0881c1c8824b188c8cbead5a472c"
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

      home = Pathname.new(ENV["HOME"].to_s.empty? ? Dir.home : ENV["HOME"])
      user_icons = home/".local/share/icons/hicolor/256x256/apps"
      user_icons.mkpath
      cp share/"icons/hicolor/256x256/apps/stelekit.png", user_icons/"stelekit.png"

      # Only wire up the launcher shortcut if it actually launches. A known
      # jpackage bug (classpath built from ~130 individual jar entries
      # overflows the native launcher's arg buffer) corrupts the launcher's
      # argv/env memory before the JVM even starts. Being memory corruption,
      # it's heap-layout dependent and shows up two ways: a hard SIGSEGV
      # (exit 139), or a garbled internal re-exec that fails with ENOENT
      # (exit 127). Silently registering a shortcut that crashes every time
      # is worse than not registering one at all.
      #
      # Cache the result in an instance variable (rather than calling
      # launcher_broken? again) so `caveats`, run later in this same install,
      # can report accurately whether registration actually happened instead
      # of unconditionally claiming success.
      @launcher_registered = !launcher_broken?
      if @launcher_registered
        # Copy desktop entry to the user's local share so app launchers that
        # don't check HOMEBREW_PREFIX (i.e. XDG_DATA_DIRS not set) find it.
        user_apps = home/".local/share/applications"
        user_apps.mkpath
        cp share/"applications/stelekit.desktop", user_apps/"stelekit.desktop"
        system "update-desktop-database", user_apps rescue nil
      else
        opoo <<~EOS
          SteleKit's launcher crashed during a startup self-test. Skipping
          app-launcher registration so it doesn't replace a working shortcut
          with a broken one. Run `#{bin}/stelekit` in a terminal to see the
          crash yourself, then `brew reinstall stelekit` once a fixed release
          is out, or open an issue: https://github.com/tstapler/stelekit/issues
        EOS
      end
    end
  end

  # Best-effort startup self-test — never raises, defaults to "assume fine"
  # (false) on any error so a broken self-test can't block installation.
  # 127 and 139 are the two observed symptoms of the classpath-overflow bug
  # (see comment above); other exit codes are left alone rather than guessed at.
  #
  # There is no headless/dry-run mode, so this launches the real production
  # binary. That's sandboxed behind a throwaway HOME/XDG_DATA_HOME (removed
  # via Dir.mktmpdir's block-form cleanup) so the probe can't leave behind a
  # real SQLite DB under the user's actual ~/.local/share/stelekit (see
  # PlatformUtils.jvm.kt / DriverFactory.jvm.kt, which resolve the DB
  # directory from XDG_DATA_HOME) or touch ~/.config/stelekit — it may still
  # briefly flash a real GUI window on a machine with a display.
  def launcher_broken?
    require "tmpdir"
    require "timeout"
    Dir.mktmpdir("stelekit-launcher-probe") do |sandbox|
      probe_env = {"HOME" => sandbox, "XDG_DATA_HOME" => "#{sandbox}/.local/share"}
      pid = Process.spawn(probe_env, (bin/"stelekit").to_s, out: File::NULL, err: File::NULL, in: File::NULL)
      begin
        Timeout.timeout(6) { Process.waitpid(pid) }
        [127, 139].include?($?&.exitstatus)
      rescue Timeout::Error
        # Still running after 6s — treat as healthy, not broken. A hang isn't
        # the classpath-overflow bug's signature (that's an immediate crash),
        # and flagging slow-but-working launches as "broken" would be wrong.
        Process.kill("TERM", pid) rescue nil
        Process.waitpid(pid) rescue nil
        false
      end
    end
  rescue StandardError
    false
  end

  def caveats
    if OS.mac?
      <<~EOS
        SteleKit has been added to ~/Applications so it appears in Finder and Launchpad.
        You can also launch it from the command line:
          stelekit
      EOS
    elsif OS.linux?
      # @launcher_registered is set by post_install (same Formula instance for
      # the whole install run). Only ever `false` when the launcher self-test
      # actually failed and registration was skipped; nil (e.g. `caveats`
      # invoked outside of an install, such as `brew info`) falls back to the
      # normal message since we have no fresher information to go on.
      if @launcher_registered == false
        <<~EOS
          SteleKit's launcher self-test failed, so the app-launcher entry was
          NOT registered (see the warning above). Run `#{bin}/stelekit` in a
          terminal to see the crash, then `brew reinstall stelekit` once a
          fixed release is out, or open an issue:
            https://github.com/tstapler/stelekit/issues

          For app launchers to also pick up future Homebrew-installed apps, add
          Homebrew's share directory to XDG_DATA_DIRS by sourcing brew shellenv
          in ~/.profile or ~/.bash_profile:
            eval "$(/home/linuxbrew/.linuxbrew/bin/brew shellenv)"
        EOS
      else
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
  end

  test do
    assert_predicate bin/"stelekit", :executable?
  end
end
