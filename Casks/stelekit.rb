# Cask managed by the release workflow — do not edit sha256/version manually.
cask "stelekit" do
  version "0.21.0"
  sha256 "fd9b3620362def9cd8f7c59d3a0e5b5b117d0351ac732a51423030838b2d0356"

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
