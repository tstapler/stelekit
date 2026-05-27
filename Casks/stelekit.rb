# Cask managed by the release workflow — do not edit sha256/version manually.
cask "stelekit" do
  version "0.28.1"
  sha256 "05ca568c5eff0007972013a73fb43f782286d087a83c467dc452b27dacc6289b"

  url "https://github.com/tstapler/stelekit/releases/download/v#{version}/SteleKit-v#{version}-macos.dmg"
  name "SteleKit"
  desc "Markdown-based outliner and note-taking app"
  homepage "https://github.com/tstapler/stelekit"

  depends_on macos: ">= :ventura"

  # Skip Gatekeeper quarantine — app is unsigned (no Apple Developer account)
  quarantine false

  app "stelekit.app"

  # CLI shim so `stelekit` works from the terminal after cask install
  binary "#{appdir}/stelekit.app/Contents/MacOS/stelekit"

  zap trash: [
    "~/Library/Application Support/SteleKit",
    "~/Library/Saved Application State/stelekit.savedState",
  ]
end
