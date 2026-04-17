- tags:: #reference #tables
- Table rendering is a planned feature. The raw syntax is shown below for reference.
	- When table support ships, tables in this graph will render automatically.
	- For now, the pipe-delimited syntax is visible as plain text.
- **GFM pipe-table syntax**
	- A table has a header row, a separator row, and data rows.
	- Example (shown as a fenced code block so it is readable today):
		- ```
| Feature         | Status    | Notes                        |
|-----------------|-----------|------------------------------|
| Bold / italic   | Supported | Use ** and * markers         |
| Inline code     | Supported | Use backticks                |
| Wiki links      | Supported | Use double-bracket links     |
| Tables          | Planned   | This page documents the plan |
| Task markers    | Planned   | See the Tasks page           |
```
- **How pipe tables work**
	- Each column is separated by a pipe character `|`.
	- The separator row uses hyphens `---` in each column cell.
	- Column alignment: `:---` = left, `:---:` = center, `---:` = right.
- **Workaround today**
	- Use nested blocks to represent tabular data until table rendering is available.
	- Example — the table above rewritten as nested blocks:
		- Bold / italic — Supported — use `**` and `*` markers
		- Inline code — Supported — use backticks
		- Wiki links — Supported — use double-bracket syntax to link pages
		- Tables — Planned — this page documents the plan
- See [[Markdown Formatting]] for all currently supported inline markup.
