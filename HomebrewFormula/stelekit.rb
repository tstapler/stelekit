# Formula managed by the release workflow — do not edit SHA256 manually.
class Stelekit < Formula
  desc "Markdown-based outliner and note-taking app (Kotlin Multiplatform)"
  homepage "https://github.com/tstapler/stelekit"
  version "0.1.0"

  on_linux do
    url "https://github.com/tstapler/stelekit/releases/download/v#{version}/SteleKit-v#{version}-linux.AppImage"
    sha256 "ea19e3d73a689f5221538c9406543b7368062f3bec02589dd17bc6e1c47b5630"
  end

  on_macos do
    url "https://github.com/tstapler/stelekit/releases/download/v#{version}/SteleKit-v#{version}-macos.dmg"
    sha256 "9f7b1681dc642ea27c30e391b97a1df116bfe47a2ba42ce07d079898241a12a4"
  end

  def install
    if OS.linux?
      bin.install "SteleKit-v#{version}-linux.AppImage" => "stelekit"
    elsif OS.mac?
      # Extract app bundle from DMG
      system "hdiutil", "attach", "-quiet", "-nobrowse", "SteleKit-v#{version}-macos.dmg"
      cp_r "/Volumes/stelekit/stelekit.app", prefix
      system "hdiutil", "detach", "-quiet", "/Volumes/stelekit"
      bin.write_exec_script "#{prefix}/stelekit.app/Contents/MacOS/stelekit"
    end
  end

  def caveats
    <<~EOS
      On Linux, SteleKit runs as an AppImage. If it fails to start, ensure
      FUSE is available or run with --appimage-extract-and-run:
        APPIMAGE_EXTRACT_AND_RUN=1 stelekit
    EOS
  end

  test do
    assert_predicate bin/"stelekit", :executable?
  end
end
