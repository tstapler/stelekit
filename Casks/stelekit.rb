# Cask managed by the release workflow — do not edit sha256/version manually.
cask "stelekit" do
  version "0.19.1"
  sha256 "b6f8ea2b2e908638516f08f344c3b2b31ecbe4b976a2898871712a62bba40f7e"

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
end
