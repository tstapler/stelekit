- tags:: #reference #formatting
- SteleKit renders standard inline Markdown within every block.
- **Text emphasis**
	- Bold: `**bold text**` → **bold text**
	- Italic: `*italic text*` → *italic text*
	- Bold and italic combined: `***bold italic***` → ***bold italic***
	- Strikethrough: `~~struck through~~` → ~~struck through~~
- **Inline code**
	- Wrap text in backticks: `` `code here` `` → `code here`
	- Use inline code for file paths, variable names, and commands.
- **Headings (in outliner mode)**
	- Add `# ` at the start of a block to render it as a heading.
	- `# Heading 1`, `## Heading 2`, `### Heading 3` are all supported.
	- Note: in an outliner, headings are rarely needed — use nesting for hierarchy instead.
- **Fenced code blocks**
	- Use triple backticks with an optional language identifier.
	- Example — a Kotlin snippet:
		- ```kotlin
val greeting = "Hello, SteleKit"
println(greeting)
```
- **Links**
	- External links: `[link text](https://example.com)`
	- Wiki links: `[[Page Linking]]` is an example — see [[Page Linking]] for full details
- **Support status**
	- Fully supported: bold, italic, strikethrough, inline code, fenced code blocks, wiki links, external links
	- Partially supported: headings (rendered but outliner hierarchy is preferred)
	- Planned: tables (see [[Tables]]), task markers (see [[Tasks]])
- See [[Block Editor Reference]] for editing shortcuts and [[Tables]] for table syntax.
