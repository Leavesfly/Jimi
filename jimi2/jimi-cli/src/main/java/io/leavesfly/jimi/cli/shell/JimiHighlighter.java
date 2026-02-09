package io.leavesfly.jimi.cli.shell;

import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.regex.Pattern;

/**
 * Jimi 语法高亮器
 * 为输入的文本提供语法高亮
 */
public class JimiHighlighter implements Highlighter {
    
    private static final Pattern META_COMMAND_PATTERN = Pattern.compile("^/\\w+");
    
    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
        AttributedStringBuilder builder = new AttributedStringBuilder();
        
        // 元命令高亮（蓝色加粗）
        if (META_COMMAND_PATTERN.matcher(buffer).find()) {
            AttributedStyle style = AttributedStyle.DEFAULT
                .foreground(AttributedStyle.BLUE)
                .bold();
            builder.styled(style, buffer);
        } else {
            // 普通文本
            builder.append(buffer);
        }
        
        return builder.toAttributedString();
    }
    
    @Override
    public void setErrorPattern(Pattern errorPattern) {
        // Not implemented
    }
    
    @Override
    public void setErrorIndex(int errorIndex) {
        // Not implemented
    }
}
