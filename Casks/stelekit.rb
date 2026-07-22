# Cask managed by the release workflow — do not edit sha256/version manually.
cask "stelekit" do
  version "0.71.3"
  sha256 "04d8c36bb2d7f4f4686ab522130c0a59af7f3eccb3ce06807ae3adfc993bded4"

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
