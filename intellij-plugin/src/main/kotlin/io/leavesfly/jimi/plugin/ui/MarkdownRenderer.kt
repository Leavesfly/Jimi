package io.leavesfly.jimi.plugin.ui

import com.intellij.ui.JBColor
import java.awt.Color
import java.util.regex.Pattern
import javax.swing.text.*

/**
 * Markdown 渲染器
 * 支持代码块、代码高亮、粗体、斜体等格式
 */
class MarkdownRenderer(private val styledDoc: StyledDocument) {
    
    private val styles = mutableMapOf<String, Style>()
    
    init {
        initStyles()
    }
    
    private fun initStyles() {
        // 代码块背景样式
        styles["code_block"] = styledDoc.addStyle("code_block", null).apply {
            StyleConstants.setFontFamily(this, "JetBrains Mono")
            StyleConstants.setFontSize(this, 12)
            StyleConstants.setBackground(this, JBColor(Color(245, 245, 245), Color(43, 43, 43)))
            StyleConstants.setForeground(this, JBColor(Color(0, 0, 0), Color(200, 200, 200)))
        }
        
        // 行内代码样式
        styles["inline_code"] = styledDoc.addStyle("inline_code", null).apply {
            StyleConstants.setFontFamily(this, "JetBrains Mono")
            StyleConstants.setFontSize(this, 12)
            StyleConstants.setBackground(this, JBColor(Color(240, 240, 240), Color(50, 50, 50)))
            StyleConstants.setForeground(this, JBColor(Color(215, 58, 73), Color(230, 219, 116)))
        }
        
        // 粗体样式
        styles["bold"] = styledDoc.addStyle("bold", null).apply {
            StyleConstants.setBold(this, true)
        }
        
        // 斜体样式
        styles["italic"] = styledDoc.addStyle("italic", null).apply {
            StyleConstants.setItalic(this, true)
        }
        
        // 标题样式
        styles["heading"] = styledDoc.addStyle("heading", null).apply {
            StyleConstants.setBold(this, true)
            StyleConstants.setFontSize(this, 15)
            StyleConstants.setForeground(this, JBColor(Color(63, 81, 181), Color(121, 134, 203)))
        }
        
        // 引用样式
        styles["quote"] = styledDoc.addStyle("quote", null).apply {
            StyleConstants.setItalic(this, true)
            StyleConstants.setForeground(this, JBColor(Color(117, 117, 117), Color(150, 150, 150)))
            StyleConstants.setLeftIndent(this, 20f)
        }
        
        // 列表样式
        styles["list"] = styledDoc.addStyle("list", null).apply {
            StyleConstants.setLeftIndent(this, 15f)
        }
        
        // 语法高亮 - 关键字
        styles["keyword"] = styledDoc.addStyle("keyword", null).apply {
            StyleConstants.setForeground(this, JBColor(Color(0, 0, 255), Color(204, 120, 50)))
            StyleConstants.setBold(this, true)
        }
        
        // 语法高亮 - 字符串
        styles["string"] = styledDoc.addStyle("string", null).apply {
            StyleConstants.setForeground(this, JBColor(Color(0, 128, 0), Color(106, 135, 89)))
        }
        
        // 语法高亮 - 注释
        styles["comment"] = styledDoc.addStyle("comment", null).apply {
            StyleConstants.setForeground(this, JBColor(Color(128, 128, 128), Color(128, 128, 128)))
            StyleConstants.setItalic(this, true)
        }
        
        // 语法高亮 - 函数
        styles["function"] = styledDoc.addStyle("function", null).apply {
            StyleConstants.setForeground(this, JBColor(Color(128, 0, 128), Color(220, 220, 170)))
        }
    }
    
    /**
     * 渲染 Markdown 文本
     */
    fun render(text: String, defaultStyle: Style) {
        val lines = text.split("\n")
        var inCodeBlock = false
        var codeBlockLanguage = ""
        val codeBlockContent = StringBuilder()
        
        for (line in lines) {
            // 检查代码块
            if (line.trim().startsWith("```")) {
                if (!inCodeBlock) {
                    // 开始代码块
                    inCodeBlock = true
                    codeBlockLanguage = line.trim().substring(3).trim()
                    codeBlockContent.clear()
                } else {
                    // 结束代码块
                    renderCodeBlock(codeBlockContent.toString(), codeBlockLanguage)
                    inCodeBlock = false
                    codeBlockLanguage = ""
                }
                continue
            }
            
            if (inCodeBlock) {
                codeBlockContent.append(line).append("\n")
                continue
            }
            
            // 渲染普通行
            renderLine(line, defaultStyle)
        }
    }
    
    /**
     * 渲染单行
     */
    private fun renderLine(line: String, defaultStyle: Style) {
        var text = line
        
        // 标题
        if (text.startsWith("#")) {
            val level = text.takeWhile { it == '#' }.length
            text = text.substring(level).trim()
            appendText(text + "\n", styles["heading"]!!)
            return
        }
        
        // 引用
        if (text.startsWith(">")) {
            text = text.substring(1).trim()
            appendText(text + "\n", styles["quote"]!!)
            return
        }
        
        // 列表
        if (text.trim().startsWith("-") || text.trim().startsWith("*") || text.trim().matches(Regex("^\\d+\\..*"))) {
            appendText(text + "\n", styles["list"]!!)
            return
        }
        
        // 处理行内格式
        renderInlineFormats(text + "\n", defaultStyle)
    }
    
    /**
     * 渲染行内格式（粗体、斜体、行内代码）
     */
    private fun renderInlineFormats(text: String, defaultStyle: Style) {
        var lastIndex = 0
        
        // 正则模式：行内代码 `code`、粗体 **bold**、斜体 *italic*
        val pattern = Pattern.compile("(`[^`]+`|\\*\\*[^*]+\\*\\*|\\*[^*]+\\*)")
        val matcher = pattern.matcher(text)
        
        while (matcher.find()) {
            // 添加前面的普通文本
            if (matcher.start() > lastIndex) {
                appendText(text.substring(lastIndex, matcher.start()), defaultStyle)
            }
            
            val matched = matcher.group()
            when {
                matched.startsWith("`") && matched.endsWith("`") -> {
                    // 行内代码
                    val code = matched.substring(1, matched.length - 1)
                    appendText(code, styles["inline_code"]!!)
                }
                matched.startsWith("**") && matched.endsWith("**") -> {
                    // 粗体
                    val bold = matched.substring(2, matched.length - 2)
                    appendText(bold, styles["bold"]!!)
                }
                matched.startsWith("*") && matched.endsWith("*") -> {
                    // 斜体
                    val italic = matched.substring(1, matched.length - 1)
                    appendText(italic, styles["italic"]!!)
                }
            }
            
            lastIndex = matcher.end()
        }
        
        // 添加剩余文本
        if (lastIndex < text.length) {
            appendText(text.substring(lastIndex), defaultStyle)
        }
    }
    
    /**
     * 渲染代码块（带语法高亮）
     */
    private fun renderCodeBlock(code: String, language: String) {
        // 添加代码块前的空行
        appendText("\n", styles["code_block"]!!)
        
        when (language.lowercase()) {
            "java", "kotlin" -> highlightJavaKotlin(code)
            "javascript", "js", "typescript", "ts" -> highlightJavaScript(code)
            "python", "py" -> highlightPython(code)
            else -> appendText(code, styles["code_block"]!!)
        }
        
        // 添加代码块后的空行
        appendText("\n\n", styles["code_block"]!!)
    }
    
    /**
     * Java/Kotlin 语法高亮
     */
    private fun highlightJavaKotlin(code: String) {
        val keywords = setOf(
            "class", "interface", "fun", "val", "var", "if", "else", "when", "for", "while",
            "return", "public", "private", "protected", "override", "abstract", "final",
            "static", "void", "int", "String", "boolean", "import", "package", "new"
        )
        
        highlightCode(code, keywords)
    }
    
    /**
     * JavaScript 语法高亮
     */
    private fun highlightJavaScript(code: String) {
        val keywords = setOf(
            "function", "const", "let", "var", "if", "else", "for", "while", "return",
            "class", "import", "export", "async", "await", "try", "catch", "new"
        )
        
        highlightCode(code, keywords)
    }
    
    /**
     * Python 语法高亮
     */
    private fun highlightPython(code: String) {
        val keywords = setOf(
            "def", "class", "if", "elif", "else", "for", "while", "return", "import",
            "from", "try", "except", "with", "as", "lambda", "yield", "async", "await"
        )
        
        highlightCode(code, keywords)
    }
    
    /**
     * 通用代码高亮
     */
    private fun highlightCode(code: String, keywords: Set<String>) {
        val lines = code.split("\n")
        
        for (line in lines) {
            var remaining = line
            
            // 检查注释
            val commentIndex = when {
                remaining.contains("//") -> remaining.indexOf("//")
                remaining.contains("#") -> remaining.indexOf("#")
                else -> -1
            }
            
            if (commentIndex >= 0) {
                // 处理注释前的代码
                highlightTokens(remaining.substring(0, commentIndex), keywords)
                // 添加注释
                appendText(remaining.substring(commentIndex) + "\n", styles["comment"]!!)
                continue
            }
            
            // 处理整行
            highlightTokens(remaining, keywords)
            appendText("\n", styles["code_block"]!!)
        }
    }
    
    /**
     * 高亮代码标记
     */
    private fun highlightTokens(line: String, keywords: Set<String>) {
        val tokens = line.split(Regex("\\b"))
        
        for (token in tokens) {
            when {
                keywords.contains(token) -> appendText(token, styles["keyword"]!!)
                token.matches(Regex("\"[^\"]*\"")) -> appendText(token, styles["string"]!!)
                token.matches(Regex("'[^']*'")) -> appendText(token, styles["string"]!!)
                token.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*\\s*\\(")) -> {
                    // 函数调用
                    val funcName = token.substringBefore("(")
                    appendText(funcName, styles["function"]!!)
                    appendText("(", styles["code_block"]!!)
                }
                else -> appendText(token, styles["code_block"]!!)
            }
        }
    }
    
    /**
     * 添加文本
     */
    private fun appendText(text: String, style: Style) {
        try {
            styledDoc.insertString(styledDoc.length, text, style)
        } catch (e: BadLocationException) {
            e.printStackTrace()
        }
    }
}
