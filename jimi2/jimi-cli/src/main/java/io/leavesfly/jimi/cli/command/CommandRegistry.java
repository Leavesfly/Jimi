package io.leavesfly.jimi.cli.command;

import io.leavesfly.jimi.adk.api.command.Command;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * CommandRegistry - 命令注册表
 * <p>
 * 负责命令的注册、查找、分类管理。
 * 线程安全设计，支持动态注册/注销。
 * </p>
 *
 * @author Jimi2 Team
 */
@Slf4j
public class CommandRegistry {

    private final Map<String, Command> commands = new ConcurrentHashMap<>();
    private final Map<String, String> aliases = new ConcurrentHashMap<>();
    private final Map<String, List<Command>> categorizedCommands = new ConcurrentHashMap<>();

    /**
     * 注册命令
     *
     * @param command 命令实例
     */
    public void register(Command command) {
        String name = command.getName().toLowerCase();

        if (commands.containsKey(name)) {
            log.warn("Command already registered: {}, overwriting", name);
        }

        commands.put(name, command);
        log.debug("Registered command: {} (category={}, priority={})",
                name, command.getCategory(), command.getPriority());

        // 注册别名
        for (String alias : command.getAliases()) {
            String aliasLower = alias.toLowerCase();
            aliases.put(aliasLower, name);
            log.debug("Registered alias: {} -> {}", aliasLower, name);
        }

        // 按分类组织
        String category = command.getCategory();
        categorizedCommands.computeIfAbsent(category, k -> new ArrayList<>())
                .add(command);

        // 按优先级排序
        categorizedCommands.get(category).sort(
                Comparator.comparingInt(Command::getPriority).reversed()
        );
    }

    /**
     * 批量注册命令
     *
     * @param commands 命令列表
     */
    public void registerAll(Collection<Command> commands) {
        commands.forEach(this::register);
    }

    /**
     * 注销命令
     *
     * @param commandName 命令名称
     */
    public void unregister(String commandName) {
        String name = commandName.toLowerCase();
        Command command = commands.remove(name);

        if (command != null) {
            // 移除别名
            for (String alias : command.getAliases()) {
                aliases.remove(alias.toLowerCase());
            }

            // 从分类中移除
            String category = command.getCategory();
            List<Command> categoryList = categorizedCommands.get(category);
            if (categoryList != null) {
                categoryList.remove(command);
                if (categoryList.isEmpty()) {
                    categorizedCommands.remove(category);
                }
            }

            log.debug("Unregistered command: {}", name);
        }
    }

    /**
     * 查找命令（支持别名）
     *
     * @param commandName 命令名称或别名
     * @return 命令实例，如果未找到则返回 null
     */
    public Command find(String commandName) {
        String name = commandName.toLowerCase();

        // 先直接查找
        Command command = commands.get(name);
        if (command != null) {
            return command;
        }

        // 通过别名查找
        String realName = aliases.get(name);
        if (realName != null) {
            return commands.get(realName);
        }

        return null;
    }

    /**
     * 获取所有命令
     *
     * @return 命令列表
     */
    public List<Command> getAllCommands() {
        return new ArrayList<>(commands.values());
    }

    /**
     * 按分类获取命令
     *
     * @return 分类映射
     */
    public Map<String, List<Command>> getCommandsByCategory() {
        return new HashMap<>(categorizedCommands);
    }

    /**
     * 获取指定分类的命令
     *
     * @param category 分类名称
     * @return 该分类下的命令列表
     */
    public List<Command> getCommandsByCategory(String category) {
        return categorizedCommands.getOrDefault(category, Collections.emptyList());
    }

    /**
     * 获取所有分类名称
     *
     * @return 分类名称列表
     */
    public Set<String> getCategories() {
        return new HashSet<>(categorizedCommands.keySet());
    }

    /**
     * 获取已注册命令数量
     *
     * @return 命令数量
     */
    public int size() {
        return commands.size();
    }

    /**
     * 检查命令是否已注册
     *
     * @param commandName 命令名称
     * @return 如果已注册返回 true
     */
    public boolean contains(String commandName) {
        return find(commandName) != null;
    }

    /**
     * 获取命令名称建议（模糊匹配）
     *
     * @param input 用户输入
     * @param limit 最大建议数量
     * @return 建议的命令名称列表
     */
    public List<String> suggest(String input, int limit) {
        String lowerInput = input.toLowerCase();

        return commands.keySet().stream()
                .filter(name -> name.startsWith(lowerInput))
                .sorted()
                .limit(limit)
                .collect(Collectors.toList());
    }
}
