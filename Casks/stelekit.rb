# Cask managed by the release workflow — do not edit sha256/version manually.
cask "stelekit" do
  version "0.26.2"
  sha256 "e06833498f3ff6a33a17da0e4c45e5266a31a3755cdee744bf4ed095ae6ee34e"

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
