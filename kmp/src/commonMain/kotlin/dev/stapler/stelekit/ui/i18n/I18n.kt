package dev.stapler.stelekit.ui.i18n

import androidx.compose.runtime.*

enum class Language(val code: String, val label: String) {
    ENGLISH("en", "English"),
    CHINESE("zh", "简体中文")
}

private val translations = mapOf(
    Language.ENGLISH to mapOf(
        "welcome" to "Welcome to SteleKit",
        "onboarding.welcome.title" to "Welcome to SteleKit",
        "onboarding.welcome.desc" to "Your knowledge, carved in stone. Local-first, yours forever.",
        "onboarding.graph.title" to "Select Your Graph",
        "onboarding.graph.desc" to "Choose where your data will be stored.",
        "onboarding.keymap.title" to "Keymap Introduction",
        "onboarding.keymap.desc" to "Learn the essential shortcuts.",
        "onboarding.finish" to "Get Started",
        "onboarding.next" to "Next",
        "onboarding.back" to "Back",
        "settings.language" to "Language",
        "common.cancel" to "Cancel",
        "common.save" to "Save",
        "common.theme" to "Theme",
        "common.settings" to "Settings",
        "common.namespace" to "Namespace",
        "common.content_placeholder" to "This is the content of the",
        "common.graph_location" to "Graph Location",
        "common.getting_started" to "Getting Started",
        "menu.file" to "File",
        "menu.switch_graph" to "Switch Graph",
        "menu.edit" to "Edit",
        "menu.view" to "View",
        "menu.help" to "Help",
        "help.sidebar_click" to "Click a page in the sidebar to open it",
        "help.search_shortcut" to "Press Ctrl+K to search or create pages",
        "help.new_page_shortcut" to "Press Ctrl+N to create a new page",
        "help.toggle_sidebar_shortcut" to "Press Ctrl+B to toggle the sidebar",
        "status.encrypted" to "Encrypted",
        "status.not_encrypted" to "Not Encrypted",
        "status.plugins_active" to "plugins active",
        "command.text.bold.label" to "Bold",
        "command.text.bold.description" to "Format selected text as bold",
        "command.text.italic.label" to "Italic",
        "command.text.italic.description" to "Format selected text as italic",
        "command.text.code.label" to "Code",
        "command.text.code.description" to "Format selected text as inline code",
        "command.text.strikethrough.label" to "Strikethrough",
        "command.text.strikethrough.description" to "Format selected text as strikethrough",
        "command.text.highlight.label" to "Highlight",
        "command.text.highlight.description" to "Highlight selected text",
        "command.text.link.label" to "Link",
        "command.text.link.description" to "Create a link from selected text",
        "command.text.heading.label" to "Heading",
        "command.text.heading.description" to "Toggle heading level",
        "command.block.new.label" to "New Block",
        "command.block.new.description" to "Create a new block",
        "command.block.delete.label" to "Delete Block",
        "command.block.delete.description" to "Delete current block",
        "command.block.move-up.label" to "Move Block Up",
        "command.block.move-up.description" to "Move current block up",
        "command.block.move-down.label" to "Move Block Down",
        "command.block.move-down.description" to "Move current block down",
        "command.block.indent.label" to "Indent Block",
        "command.block.indent.description" to "Indent current block",
        "command.block.outdent.label" to "Outdent Block",
        "command.block.outdent.description" to "Outdent current block",
        "command.block.toggle-todo.label" to "Toggle Todo",
        "command.block.toggle-todo.description" to "Toggle todo status",
        "command.navigation.search.label" to "Search",
        "command.navigation.search.description" to "Open search dialog",
        "command.navigation.goto-page.label" to "Go to Page",
        "command.navigation.goto-page.description" to "Open page selector",
        "command.navigation.back.label" to "Back",
        "command.navigation.back.description" to "Navigate back",
        "command.navigation.forward.label" to "Forward",
        "command.navigation.forward.description" to "Navigate forward",
        "command.system.save.label" to "Save",
        "command.system.save.description" to "Save current document",
        "command.system.undo.label" to "Undo",
        "command.system.undo.description" to "Undo last action",
        "command.system.redo.label" to "Redo",
        "command.system.redo.description" to "Redo last undone action",
        "command.system.settings.label" to "Settings",
        "command.system.settings.description" to "Open settings",
        "command.system.help.label" to "Help",
        "command.system.help.description" to "Open help documentation",
        "command.palette.placeholder" to "Type a command or search...",
        "command.palette.slash.placeholder" to "Type a slash command...",
        "command.palette.no-results" to "No commands found",
        "nav.section.daily" to "Daily",
        "nav.section.connections" to "Connections",
        "nav.section.graph" to "Graph",
        "nav.section.developer" to "Developer",
        "nav.journals" to "Journals",
        "nav.journals.desc" to "Your daily notes, newest first",
        "nav.all_pages" to "All pages",
        "nav.pages_short" to "Pages",
        "nav.all_pages.desc" to "Every page in this graph",
        "nav.flashcards" to "Flashcards",
        "nav.flashcards.desc" to "Review cards you have made",
        "nav.unlinked_refs" to "Unlinked References",
        "nav.unlinked_refs.desc" to "Mentions that are not yet links",
        "nav.notifications" to "Notifications",
        "nav.notifications.desc" to "Alerts and suggestions",
        "nav.library" to "Library",
        "nav.library.desc" to "Every photo and file on your pages",
        "nav.graph_stats" to "Graph Stats",
        "nav.graph_stats.desc" to "Size and health of this graph",
        "nav.sketch" to "Sketch",
        "nav.sketch.desc" to "Draw plans and elevations",
        "nav.git_setup" to "Git Setup",
        "nav.git_setup.desc" to "Sync this graph with a repository",
        "nav.logs" to "Logs",
        "nav.logs.desc" to "Diagnostic log viewer",
        "nav.performance" to "Performance",
        "nav.performance.desc" to "Timing and memory",
        "nav.go_to" to "Go to",
        "nav.new_badge" to "New",
        "nav.new_badge.state" to "New: Gallery and Assets moved here",
        "nav.more" to "More",
        "library.title" to "Library",
        "library.search.placeholder" to "Search library",
        "library.search.hint" to "Type at least 2 characters to search",
        "library.filter.group.type" to "Filter by media type",
        "library.filter.group.show" to "Show",
        "library.filter.group.tags" to "Filter by tag",
        "library.filter.annotated" to "Annotated",
        "library.filter.orphaned" to "Orphaned",
        "library.filter.images" to "Images",
        "library.filter.pdfs" to "PDFs",
        "library.filter.audio" to "Audio",
        "library.filter.video" to "Video",
        "library.filter.documents" to "Docs",
        "library.filter.other" to "Other",
        "library.filter.clear" to "Clear filters",
        "library.filter.reason.annotate_needs_photos" to "Unavailable: only photos can be annotated",
        "library.filter.reason.type_not_photo" to "Unavailable: a non-photo type is selected",
        "library.sort" to "Sort",
        "library.sort.recently_added" to "Recently added",
        "library.sort.recently_annotated" to "Recently annotated",
        "library.sort.most_measurements" to "Most measurements",
        "library.sort.name" to "Name A-Z",
        "library.empty.title" to "Nothing in your library yet",
        "library.empty.body" to "Capture a photo or attach a file; it is added to the page you were last on.",
        "library.empty.filtered" to "No items match these filters",
        "library.empty.annotated" to "No annotated photos yet. Open a photo and add a measurement.",
        "library.error.title" to "Could not load the library",
        "library.error.retry" to "Retry",
        "library.load_more" to "Load more",
        "library.badge.annotated" to "Annotated",
        "library.tip" to "Everything attached to your pages lives here. Tap Annotated to see photos you've annotated.",
        "library.notice" to "Gallery and Assets are now Library. Use the Annotated filter to see your annotated photos.",
        "library.notice.dismiss" to "Got it",
        "library.notice.show_annotated" to "Show annotated photos",
        "library.cmd.show_annotated" to "Show annotated photos",
        "library.cmd.show_annotated.desc" to "Open the Library filtered to annotated photos",
        "library.search.section" to "Library",
        "library.search.show_all" to "Show all in Library",
        "nav.more.drawer" to "Graphs, favorites and sync"
    ),
    Language.CHINESE to mapOf(
        "welcome" to "欢迎使用 SteleKit",
        "onboarding.welcome.title" to "欢迎使用 SteleKit",
        "onboarding.welcome.desc" to "隐私优先的知识管理平台。",
        "onboarding.graph.title" to "选择您的图谱",
        "onboarding.graph.desc" to "选择您的数据存储位置。",
        "onboarding.keymap.title" to "快捷键介绍",
        "onboarding.keymap.desc" to "学习常用快捷键。",
        "onboarding.finish" to "开始使用",
        "onboarding.next" to "下一步",
        "onboarding.back" to "上一步",
        "settings.language" to "语言",
        "common.cancel" to "取消",
        "common.save" to "保存",
        "common.theme" to "主题",
        "common.settings" to "设置",
        "common.namespace" to "命名空间",
        "common.content_placeholder" to "这是页面的内容：",
        "common.graph_location" to "图谱位置",
        "common.getting_started" to "开始使用",
        "menu.file" to "文件",
        "menu.switch_graph" to "切换图谱",
        "menu.edit" to "编辑",
        "menu.view" to "视图",
        "menu.help" to "帮助",
        "help.sidebar_click" to "点击侧边栏中的页面以打开它",
        "help.search_shortcut" to "按 Ctrl+K 搜索或创建页面",
        "help.new_page_shortcut" to "按 Ctrl+N 创建新页面",
        "help.toggle_sidebar_shortcut" to "按 Ctrl+B 切换侧边栏",
        "status.encrypted" to "已加密",
        "status.not_encrypted" to "未加密",
        "status.plugins_active" to "个插件已激活",
        "command.text.bold.label" to "粗体",
        "command.text.bold.description" to "将选中文本格式化为粗体",
        "command.text.italic.label" to "斜体",
        "command.text.italic.description" to "将选中文本格式化为斜体",
        "command.text.code.label" to "代码",
        "command.text.code.description" to "将选中文本格式化为内联代码",
        "command.text.strikethrough.label" to "删除线",
        "command.text.strikethrough.description" to "为选中文本添加删除线",
        "command.text.highlight.label" to "高亮",
        "command.text.highlight.description" to "高亮选中文本",
        "command.text.link.label" to "链接",
        "command.text.link.description" to "从选中文本创建链接",
        "command.text.heading.label" to "标题",
        "command.text.heading.description" to "切换标题级别",
        "command.block.new.label" to "新建块",
        "command.block.new.description" to "创建新块",
        "command.block.delete.label" to "删除块",
        "command.block.delete.description" to "删除当前块",
        "command.block.move-up.label" to "向上移动块",
        "command.block.move-up.description" to "将当前块向上移动",
        "command.block.move-down.label" to "向下移动块",
        "command.block.move-down.description" to "将当前块向下移动",
        "command.block.indent.label" to "缩进块",
        "command.block.indent.description" to "缩进当前块",
        "command.block.outdent.label" to "取消缩进块",
        "command.block.outdent.description" to "取消缩进当前块",
        "command.block.toggle-todo.label" to "切换待办",
        "command.block.toggle-todo.description" to "切换待办状态",
        "command.navigation.search.label" to "搜索",
        "command.navigation.search.description" to "打开搜索对话框",
        "command.navigation.goto-page.label" to "转到页面",
        "command.navigation.goto-page.description" to "打开页面选择器",
        "command.navigation.back.label" to "后退",
        "command.navigation.back.description" to "向后导航",
        "command.navigation.forward.label" to "前进",
        "command.navigation.forward.description" to "向前导航",
        "command.system.save.label" to "保存",
        "command.system.save.description" to "保存当前文档",
        "command.system.undo.label" to "撤销",
        "command.system.undo.description" to "撤销上一个操作",
        "command.system.redo.label" to "重做",
        "command.system.redo.description" to "重做上一个撤销的操作",
        "command.system.settings.label" to "设置",
        "command.system.settings.description" to "打开设置",
        "command.system.help.label" to "帮助",
        "command.system.help.description" to "打开帮助文档",
        "command.palette.placeholder" to "输入命令或搜索...",
        "command.palette.slash.placeholder" to "输入斜杠命令...",
        "command.palette.no-results" to "未找到命令"
    )
)

class I18n(val language: Language) {
    fun t(key: String): String {
        return translations[language]?.get(key) ?: translations[Language.ENGLISH]?.get(key) ?: key
    }

    companion object {
        const val NAV_SECTION_DAILY = "nav.section.daily"
        const val NAV_SECTION_CONNECTIONS = "nav.section.connections"
        const val NAV_SECTION_GRAPH = "nav.section.graph"
        const val NAV_SECTION_DEVELOPER = "nav.section.developer"
        const val NAV_JOURNALS = "nav.journals"
        const val NAV_JOURNALS_DESC = "nav.journals.desc"
        const val NAV_ALL_PAGES = "nav.all_pages"
        const val NAV_ALL_PAGES_DESC = "nav.all_pages.desc"
        const val NAV_PAGES_SHORT = "nav.pages_short"
        const val NAV_FLASHCARDS = "nav.flashcards"
        const val NAV_FLASHCARDS_DESC = "nav.flashcards.desc"
        const val NAV_UNLINKED_REFS = "nav.unlinked_refs"
        const val NAV_UNLINKED_REFS_DESC = "nav.unlinked_refs.desc"
        const val NAV_NOTIFICATIONS = "nav.notifications"
        const val NAV_NOTIFICATIONS_DESC = "nav.notifications.desc"
        const val NAV_LIBRARY = "nav.library"
        const val NAV_LIBRARY_DESC = "nav.library.desc"
        const val NAV_GRAPH_STATS = "nav.graph_stats"
        const val NAV_GRAPH_STATS_DESC = "nav.graph_stats.desc"
        const val NAV_SKETCH = "nav.sketch"
        const val NAV_SKETCH_DESC = "nav.sketch.desc"
        const val NAV_GIT_SETUP = "nav.git_setup"
        const val NAV_GIT_SETUP_DESC = "nav.git_setup.desc"
        const val NAV_LOGS = "nav.logs"
        const val NAV_LOGS_DESC = "nav.logs.desc"
        const val NAV_PERFORMANCE = "nav.performance"
        const val NAV_PERFORMANCE_DESC = "nav.performance.desc"
        const val NAV_GO_TO = "nav.go_to"
        const val NAV_NEW_BADGE = "nav.new_badge"
        const val NAV_NEW_BADGE_STATE = "nav.new_badge.state"
        const val NAV_MORE = "nav.more"
        const val NAV_MORE_DRAWER = "nav.more.drawer"

        const val LIBRARY_TITLE = "library.title"
        const val LIBRARY_SEARCH_PLACEHOLDER = "library.search.placeholder"
        const val LIBRARY_SEARCH_HINT = "library.search.hint"
        const val LIBRARY_GROUP_TYPE = "library.filter.group.type"
        const val LIBRARY_GROUP_SHOW = "library.filter.group.show"
        const val LIBRARY_GROUP_TAGS = "library.filter.group.tags"
        const val LIBRARY_FILTER_ANNOTATED = "library.filter.annotated"
        const val LIBRARY_FILTER_ORPHANED = "library.filter.orphaned"
        const val LIBRARY_FILTER_IMAGES = "library.filter.images"
        const val LIBRARY_FILTER_PDFS = "library.filter.pdfs"
        const val LIBRARY_FILTER_AUDIO = "library.filter.audio"
        const val LIBRARY_FILTER_VIDEO = "library.filter.video"
        const val LIBRARY_FILTER_DOCUMENTS = "library.filter.documents"
        const val LIBRARY_FILTER_OTHER = "library.filter.other"
        const val LIBRARY_FILTER_CLEAR = "library.filter.clear"
        const val LIBRARY_REASON_ANNOTATE_NEEDS_PHOTOS = "library.filter.reason.annotate_needs_photos"
        const val LIBRARY_REASON_TYPE_NOT_PHOTO = "library.filter.reason.type_not_photo"
        const val LIBRARY_SORT = "library.sort"
        const val LIBRARY_SORT_RECENTLY_ADDED = "library.sort.recently_added"
        const val LIBRARY_SORT_RECENTLY_ANNOTATED = "library.sort.recently_annotated"
        const val LIBRARY_SORT_MOST_MEASUREMENTS = "library.sort.most_measurements"
        const val LIBRARY_SORT_NAME = "library.sort.name"
        const val LIBRARY_EMPTY_TITLE = "library.empty.title"
        const val LIBRARY_EMPTY_BODY = "library.empty.body"
        const val LIBRARY_EMPTY_FILTERED = "library.empty.filtered"
        const val LIBRARY_EMPTY_ANNOTATED = "library.empty.annotated"
        const val LIBRARY_ERROR_TITLE = "library.error.title"
        const val LIBRARY_ERROR_RETRY = "library.error.retry"
        const val LIBRARY_LOAD_MORE = "library.load_more"
        const val LIBRARY_BADGE_ANNOTATED = "library.badge.annotated"
        const val LIBRARY_TIP = "library.tip"
        const val LIBRARY_NOTICE = "library.notice"
        const val LIBRARY_NOTICE_DISMISS = "library.notice.dismiss"
        const val LIBRARY_NOTICE_SHOW_ANNOTATED = "library.notice.show_annotated"
        const val LIBRARY_CMD_SHOW_ANNOTATED = "library.cmd.show_annotated"
        const val LIBRARY_CMD_SHOW_ANNOTATED_DESC = "library.cmd.show_annotated.desc"
        const val LIBRARY_SEARCH_SECTION = "library.search.section"
        const val LIBRARY_SEARCH_SHOW_ALL = "library.search.show_all"

        /** English lookup for non-composable callers (palette commands, tests). */
        fun get(key: String): String = I18n(Language.ENGLISH).t(key)
    }
}

val LocalI18n = staticCompositionLocalOf { I18n(Language.ENGLISH) }

@Composable
fun t(key: String): String {
    return LocalI18n.current.t(key)
}
