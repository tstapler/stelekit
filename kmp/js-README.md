# JS/IR to JS Converter for Logseq KMP

This script converts Kotlin IR (.knm) files to executable JavaScript using the Kotlin/JS compiler.

## Current Status

- JS compilation produces `.knm` files (Kotlin Intermediate Representation)
- Need additional linking step to convert IR to executable JS
- The IR files are located in `kmp/build/classes/kotlin/js/main/default/`

## Next Steps

1. Add JS compilation dependencies
2. Configure IR-to-JS linking 
3. Generate proper module exports

## Build Commands

```bash
# Current working commands
./gradlew :kmp:compileKotlinJs    # Compiles to IR (.knm)
./gradlew :kmp:jsJar             # Packages IR into .klib

# To run the app:
cd kmp/build/distributions
python3 -m http.server 8000  # Open index.html
```