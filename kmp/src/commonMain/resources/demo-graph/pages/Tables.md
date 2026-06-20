- tags:: #reference #tables
- SteleKit renders GFM pipe tables natively inside blocks.
- **Syntax**
	- A table has a header row, a separator row, and data rows.
	- Each column is separated by a pipe character `|`.
	- The separator row uses hyphens `---` in each column cell.
	- Column alignment: `:---` = left, `:---:` = center, `---:` = right.
- **Example — feature support table**

| Feature         | Status    | Notes                             |
|:----------------|:---------:|----------------------------------:|
| Bold / italic   | Supported | Use `**` and `*` markers          |
| Inline code     | Supported | Use backticks                     |
| Highlight       | Supported | Use `==text==`                    |
| Wiki links      | Supported | Use `[[double bracket]]` syntax   |
| Tables          | Supported | GFM pipe-table syntax (this page) |
| Tags            | Supported | Use `#tag-name` inline            |
| Task markers    | Supported | TODO, DOING, DONE prefix syntax   |

- **Tips**
	- Tables render inside any block — paste the markdown directly.
	- Use alignment markers to control column layout: `:---` left, `:---:` center, `---:` right.
	- For simple comparisons, nested blocks can also work well as an alternative.
- See [[Markdown Formatting]] for all supported inline markup, and [[Properties]] for `key:: value` metadata syntax.
