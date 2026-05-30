# Cask managed by the release workflow — do not edit sha256/version manually.
cask "stelekit" do
  version "0.31.1"
  sha256 "14fb447fdb547146616d34576beaa50e8531c0d59b0c6a8e0825e651f0acba3c"

  url "https://github.com/tstapler/stelekit/releases/download/v#{version}/SteleKit-v#{version}-macos.dmg"
  name "SteleKit"
  desc "Markdown-based outliner and note-taking app"
  homepage "https://github.com/tstapler/stelekit"

  depends_on macos: ">= :ventura"

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
