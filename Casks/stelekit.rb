# Cask managed by the release workflow — do not edit sha256/version manually.
cask "stelekit" do
  version "0.41.3"
  sha256 "98f4bcc4367372c44d63abf80aa9201cb7c2acc6d8e1f4d718be36e56dd4c5ce"

  url "https://github.com/tstapler/stelekit/releases/download/v#{version}/SteleKit-v#{version}-macos.dmg"
  name "SteleKit"
  desc "Markdown-based outliner and note-taking app"
  homepage "https://github.com/tstapler/stelekit"

  depends_on macos: :ventura

  app "stelekit.app"

  # CLI shim so `stelekit` works from the terminal after cask install
  binary "#{appdir}/stelekit.app/Contents/MacOS/stelekit"

  zap trash: [
    "~/Library/Application Support/SteleKit",
    "~/Library/Saved Application State/stelekit.savedState",
  ]

  caveats <<~EOS
    SteleKit is unsigned. If macOS Gatekeeper blocks the app on first launch,
    right-click the app in Finder and choose "Open", or reinstall with:
      brew install --cask --no-quarantine tstapler/stelekit/stelekit
  EOS
end
