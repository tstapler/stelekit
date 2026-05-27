# Cask managed by the release workflow — do not edit sha256/version manually.
cask "stelekit" do
  version "0.30.0"
  sha256 "9641f8ee52a5e82bcd69acc88168a6762719871a3da5eecbf89cd9c00d79cce4"

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
