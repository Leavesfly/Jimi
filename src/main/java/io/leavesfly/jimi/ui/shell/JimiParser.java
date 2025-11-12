package io.leavesfly.jimi.ui.shell;

import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.impl.DefaultParser;

/**
 * Jimi 命令解析器
 * 解析用户输入的命令
 */
public class JimiParser implements Parser {
    
    private final DefaultParser defaultParser;
    
    public JimiParser() {
        this.defaultParser = new DefaultParser();
        // 禁用转义字符处理
        this.defaultParser.setEscapeChars(null);
        // 保留引号处理以支持补全
        // this.defaultParser.setQuoteChars(null);  // 注释掉，保持默认引号处理
    }
    
    @Override
    public ParsedLine parse(String line, int cursor, ParseContext context) {
        return defaultParser.parse(line, cursor, context);
    }
    
    @Override
    public boolean isEscapeChar(char ch) {
        return false;
    }
    
    @Override
    public boolean validCommandName(String name) {
        return true;
    }
    
    @Override
    public boolean validVariableName(String name) {
        return true;
    }
    
    @Override
    public String getCommand(String line) {
        return defaultParser.getCommand(line);
    }
    
    @Override
    public String getVariable(String line) {
        return defaultParser.getVariable(line);
    }
}
