# Cask managed by the release workflow — do not edit sha256/version manually.
cask "stelekit" do
  version "0.38.4"
  sha256 "d0d4bdb89f233cb9e75399aa378aa425774a4451aa9de2d0a97425eb0a666a44"

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
