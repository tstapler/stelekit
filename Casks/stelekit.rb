# Cask managed by the release workflow — do not edit sha256/version manually.
cask "stelekit" do
  version "0.53.1"
  sha256 "eed3de6d6e3290ed72af7b990bb3defd9027877253ffcf10eb4e20b3d3df836e"

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
    SteleKit is ad-hoc signed but not notarized. If macOS Gatekeeper blocks
    the app on first launch, right-click the app in Finder and choose "Open",
    or reinstall without the quarantine flag:
      brew install --cask --no-quarantine tstapler/stelekit/stelekit
  EOS
end
