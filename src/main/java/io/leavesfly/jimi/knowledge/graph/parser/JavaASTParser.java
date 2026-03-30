package io.leavesfly.jimi.knowledge.graph.parser;

import com.github.javaparser.JavaParser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.MethodCallExpr;

import io.leavesfly.jimi.knowledge.graph.model.CodeEntity;
import io.leavesfly.jimi.knowledge.graph.model.CodeRelation;
import io.leavesfly.jimi.knowledge.graph.model.EntityType;
import io.leavesfly.jimi.knowledge.graph.model.RelationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Java AST 解析器
 * <p>
 * 使用 JavaParser 解析 Java 源代码，提取代码实体和关系
 */
@Slf4j
@Component
public class JavaASTParser implements LanguageParser {
    
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".java");
    
    @Override
    public String getLanguageName() {
        return "Java";
    }
    
    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }
    
    private final JavaParser javaParser;
    
    public JavaASTParser() {
        this.javaParser = new JavaParser();
    }
    
    /**
     * 解析 Java 文件
     *
     * @param filePath 文件路径
     * @param projectRoot 项目根目录 (用于计算相对路径)
     * @return 解析结果
     */
    @Override
    public ParseResult parseFile(Path filePath, Path projectRoot) {
        ParseResult result = ParseResult.builder()
            .filePath(projectRoot.relativize(filePath).toString())
            .build();
        
        try {
            // 读取文件内容
            String content = Files.readString(filePath);
            
            // 解析 Java 代码 (使用 JavaParser 库的 ParseResult)
            com.github.javaparser.ParseResult<CompilationUnit> parseResult = javaParser.parse(content);
            
            if (!parseResult.isSuccessful() || !parseResult.getResult().isPresent()) {
                log.warn("Failed to parse file: {}", filePath);
                return ParseResult.failure(
                    result.getFilePath(), 
                    "Parse failed");
            }
            
            CompilationUnit cu = parseResult.getResult().get();
            
            // 提取包名
            String packageName = cu.getPackageDeclaration()
                .map(PackageDeclaration::getNameAsString)
                .orElse("");
            
            // 创建文件实体
            CodeEntity fileEntity = createFileEntity(result.getFilePath(), packageName);
            result.addEntity(fileEntity);
            
            // 解析类型声明 (类、接口、枚举)
            cu.getTypes().forEach(typeDecl -> {
                parseTypeDeclaration(typeDecl, packageName, fileEntity, result);
            });
            
            log.debug("Parsed file: {} - {}", filePath.getFileName(), result.getStats());
            
        } catch (Exception e) {
            log.error("Error parsing file: {}", filePath, e);
            return io.leavesfly.jimi.knowledge.graph.parser.ParseResult.failure(
                result.getFilePath(), 
                e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 创建文件实体
     */
    private CodeEntity createFileEntity(String filePath, String packageName) {
        String qualifiedName = packageName.isEmpty() ? filePath : packageName + "." + filePath;
        return CodeEntity.builder()
            .id(CodeEntity.generateId(EntityType.FILE, qualifiedName))
            .type(EntityType.FILE)
            .name(filePath.substring(filePath.lastIndexOf('/') + 1))
            .qualifiedName(qualifiedName)
            .filePath(filePath)
            .build();
    }
    
    /**
     * 解析类型声明
     */
    private void parseTypeDeclaration(TypeDeclaration<?> typeDecl, String packageName, 
                                     CodeEntity fileEntity, io.leavesfly.jimi.knowledge.graph.parser.ParseResult result) {
        String typeName = typeDecl.getNameAsString();
        String qualifiedName = packageName.isEmpty() ? typeName : packageName + "." + typeName;
        
        // 确定实体类型
        EntityType entityType;
        if (typeDecl.isClassOrInterfaceDeclaration()) {
            ClassOrInterfaceDeclaration classOrInterface = typeDecl.asClassOrInterfaceDeclaration();
            entityType = classOrInterface.isInterface() ? EntityType.INTERFACE : EntityType.CLASS;
        } else if (typeDecl.isEnumDeclaration()) {
            entityType = EntityType.ENUM;
        } else if (typeDecl.isAnnotationDeclaration()) {
            entityType = EntityType.ANNOTATION;
        } else {
            return; // 未知类型，跳过
        }
        
        // 创建类型实体
        CodeEntity typeEntity = CodeEntity.builder()
            .id(CodeEntity.generateId(entityType, qualifiedName))
            .type(entityType)
            .name(typeName)
            .qualifiedName(qualifiedName)
            .filePath(result.getFilePath())
            .startLine(typeDecl.getBegin().map(pos -> pos.line).orElse(null))
            .endLine(typeDecl.getEnd().map(pos -> pos.line).orElse(null))
            .visibility(extractVisibility(typeDecl))
            .isAbstract(typeDecl.isClassOrInterfaceDeclaration() && 
                       typeDecl.asClassOrInterfaceDeclaration().isAbstract())
            .build();
        
        result.addEntity(typeEntity);
        result.addSymbol(typeName, qualifiedName);
        
        // 添加文件包含类型的关系
        result.addRelation(CodeRelation.builder()
            .sourceId(fileEntity.getId())
            .targetId(typeEntity.getId())
            .type(RelationType.CONTAINS)
            .build());
        
        // 解析类的继承和实现关系
        if (typeDecl.isClassOrInterfaceDeclaration()) {
            ClassOrInterfaceDeclaration classDecl = typeDecl.asClassOrInterfaceDeclaration();
            parseInheritance(classDecl, typeEntity, result);
        }
        
        // 解析类成员 (方法、字段、构造函数)
        typeDecl.getMembers().forEach(member -> {
            parseMember(member, typeEntity, qualifiedName, result);
        });
    }
    
    /**
     * 解析继承关系
     */
    private void parseInheritance(ClassOrInterfaceDeclaration classDecl, CodeEntity classEntity,
                                  io.leavesfly.jimi.knowledge.graph.parser.ParseResult result) {
        // 解析继承 (extends)
        classDecl.getExtendedTypes().forEach(extendedType -> {
            String superClassName = extendedType.getNameAsString();
            String superClassId = CodeEntity.generateId(EntityType.CLASS, superClassName);
            
            result.addRelation(CodeRelation.builder()
                .sourceId(classEntity.getId())
                .targetId(superClassId)
                .type(RelationType.EXTENDS)
                .build());
        });
        
        // 解析实现 (implements)
        classDecl.getImplementedTypes().forEach(implementedType -> {
            String interfaceName = implementedType.getNameAsString();
            String interfaceId = CodeEntity.generateId(EntityType.INTERFACE, interfaceName);
            
            result.addRelation(CodeRelation.builder()
                .sourceId(classEntity.getId())
                .targetId(interfaceId)
                .type(RelationType.IMPLEMENTS)
                .build());
        });
    }
    
    /**
     * 解析类成员
     */
    private void parseMember(BodyDeclaration<?> member, CodeEntity classEntity, String classQualifiedName,
                            io.leavesfly.jimi.knowledge.graph.parser.ParseResult result) {
        if (member.isMethodDeclaration()) {
            parseMethod(member.asMethodDeclaration(), classEntity, classQualifiedName, result);
        } else if (member.isConstructorDeclaration()) {
            parseConstructor(member.asConstructorDeclaration(), classEntity, classQualifiedName, result);
        } else if (member.isFieldDeclaration()) {
            parseField(member.asFieldDeclaration(), classEntity, classQualifiedName, result);
        }
    }
    
    /**
     * 解析方法
     */
    private void parseMethod(MethodDeclaration method, CodeEntity classEntity, String classQualifiedName,
                            io.leavesfly.jimi.knowledge.graph.parser.ParseResult result) {
        String methodName = method.getNameAsString();
        String signature = methodName + "(" + method.getParameters().size() + ")";
        String qualifiedName = classQualifiedName + "." + signature;
        
        CodeEntity methodEntity = CodeEntity.builder()
            .id(CodeEntity.generateId(EntityType.METHOD, qualifiedName))
            .type(EntityType.METHOD)
            .name(methodName)
            .qualifiedName(qualifiedName)
            .filePath(result.getFilePath())
            .startLine(method.getBegin().map(pos -> pos.line).orElse(null))
            .endLine(method.getEnd().map(pos -> pos.line).orElse(null))
            .visibility(extractVisibility(method))
            .isStatic(method.isStatic())
            .isAbstract(method.isAbstract())
            .build();
        
        // 添加签名信息
        methodEntity.addAttribute("signature", signature);
        methodEntity.addAttribute("returnType", method.getTypeAsString());
        
        result.addEntity(methodEntity);
        
        // 添加类包含方法的关系
        result.addRelation(CodeRelation.builder()
            .sourceId(classEntity.getId())
            .targetId(methodEntity.getId())
            .type(RelationType.CONTAINS)
            .build());
        
        // 解析方法调用
        method.getBody().ifPresent(body -> {
            parseMethodCalls(body, methodEntity, result);
        });
    }
    
    /**
     * 解析构造函数
     */
    private void parseConstructor(ConstructorDeclaration constructor, CodeEntity classEntity, 
                                  String classQualifiedName, io.leavesfly.jimi.knowledge.graph.parser.ParseResult result) {
        String constructorName = constructor.getNameAsString();
        String signature = constructorName + "(" + constructor.getParameters().size() + ")";
        String qualifiedName = classQualifiedName + "." + signature;
        
        CodeEntity constructorEntity = CodeEntity.builder()
            .id(CodeEntity.generateId(EntityType.CONSTRUCTOR, qualifiedName))
            .type(EntityType.CONSTRUCTOR)
            .name(constructorName)
            .qualifiedName(qualifiedName)
            .filePath(result.getFilePath())
            .startLine(constructor.getBegin().map(pos -> pos.line).orElse(null))
            .endLine(constructor.getEnd().map(pos -> pos.line).orElse(null))
            .visibility(extractVisibility(constructor))
            .build();
        
        result.addEntity(constructorEntity);
        
        // 添加类包含构造函数的关系
        result.addRelation(CodeRelation.builder()
            .sourceId(classEntity.getId())
            .targetId(constructorEntity.getId())
            .type(RelationType.CONTAINS)
            .build());
    }
    
    /**
     * 解析字段
     */
    private void parseField(FieldDeclaration field, CodeEntity classEntity, String classQualifiedName,
                           io.leavesfly.jimi.knowledge.graph.parser.ParseResult result) {
        field.getVariables().forEach(variable -> {
            String fieldName = variable.getNameAsString();
            String qualifiedName = classQualifiedName + "." + fieldName;
            
            CodeEntity fieldEntity = CodeEntity.builder()
                .id(CodeEntity.generateId(EntityType.FIELD, qualifiedName))
                .type(EntityType.FIELD)
                .name(fieldName)
                .qualifiedName(qualifiedName)
                .filePath(result.getFilePath())
                .startLine(field.getBegin().map(pos -> pos.line).orElse(null))
                .endLine(field.getEnd().map(pos -> pos.line).orElse(null))
                .visibility(extractVisibility(field))
                .isStatic(field.isStatic())
                .build();
            
            fieldEntity.addAttribute("fieldType", variable.getTypeAsString());
            
            result.addEntity(fieldEntity);
            
            // 添加类包含字段的关系
            result.addRelation(CodeRelation.builder()
                .sourceId(classEntity.getId())
                .targetId(fieldEntity.getId())
                .type(RelationType.CONTAINS)
                .build());
        });
    }
    
    /**
     * 解析方法调用
     * 尝试创建 CALLS 关系（简化版本，处理同类方法调用和已知类型的调用）
     */
    private void parseMethodCalls(Node node, CodeEntity methodEntity, 
                                  io.leavesfly.jimi.knowledge.graph.parser.ParseResult result) {
        String currentClassQualifiedName = extractClassQualifiedName(methodEntity.getQualifiedName());
        
        // 查找方法调用表达式
        node.findAll(MethodCallExpr.class).forEach(methodCall -> {
            String calledMethodName = methodCall.getNameAsString();
            int paramCount = methodCall.getArguments().size();
            
            // 记录调用属性（用于调试和分析）
            methodEntity.addAttribute("calls_" + calledMethodName, true);
            
            // 尝试解析调用目标
            String targetMethodId = resolveMethodCallTarget(methodCall, calledMethodName, 
                                                            paramCount, currentClassQualifiedName);
            
            if (targetMethodId != null) {
                // 创建 CALLS 关系
                result.addRelation(CodeRelation.builder()
                    .sourceId(methodEntity.getId())
                    .targetId(targetMethodId)
                    .type(RelationType.CALLS)
                    .build());
            }
        });
    }
    
    /**
     * 尝试解析方法调用的目标方法 ID
     * 
     * @param methodCall 方法调用表达式
     * @param methodName 方法名
     * @param paramCount 参数数量
     * @param currentClassQualifiedName 当前类的全限定名
     * @return 目标方法的 ID，无法解析时返回 null
     */
    private String resolveMethodCallTarget(MethodCallExpr methodCall, String methodName, 
                                           int paramCount, String currentClassQualifiedName) {
        // 尝试获取调用范围（调用者）
        if (methodCall.getScope().isEmpty()) {
            // 无范围调用，可能是同类方法调用或静态导入
            // 假设是同类方法调用
            if (currentClassQualifiedName != null) {
                String signature = methodName + "(" + paramCount + ")";
                return CodeEntity.generateId(EntityType.METHOD, 
                    currentClassQualifiedName + "." + signature);
            }
        } else {
            // 有范围调用，如 object.method() 或 ClassName.staticMethod()
            String scope = methodCall.getScope().get().toString();
            
            // 如果范围是简单的类名（首字母大写），假设是静态方法调用
            if (scope.matches("[A-Z][a-zA-Z0-9]*")) {
                String signature = methodName + "(" + paramCount + ")";
                return CodeEntity.generateId(EntityType.METHOD, scope + "." + signature);
            }
            
            // 对于 this.method() 形式
            if ("this".equals(scope) && currentClassQualifiedName != null) {
                String signature = methodName + "(" + paramCount + ")";
                return CodeEntity.generateId(EntityType.METHOD, 
                    currentClassQualifiedName + "." + signature);
            }
            
            // 其他情况（如 object.method()）需要符号解析器支持
            // 这里返回 null，暂不处理
        }
        
        return null;
    }
    
    /**
     * 从方法的全限定名中提取类的全限定名
     */
    private String extractClassQualifiedName(String methodQualifiedName) {
        if (methodQualifiedName == null) {
            return null;
        }
        // 方法全限定名格式: com.example.ClassName.methodName(paramCount)
        // 需要提取: com.example.ClassName
        int lastDotIndex = methodQualifiedName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            String beforeMethod = methodQualifiedName.substring(0, lastDotIndex);
            // 再去掉签名部分
            int signatureStart = beforeMethod.indexOf('(');
            if (signatureStart > 0) {
                beforeMethod = beforeMethod.substring(0, signatureStart);
            }
            return beforeMethod;
        }
        return null;
    }
    
    /**
     * 提取可见性
     */
    private String extractVisibility(TypeDeclaration<?> typeDecl) {
        return typeDecl.getAccessSpecifier().asString();
    }
    
    private String extractVisibility(MethodDeclaration method) {
        return method.getAccessSpecifier().asString();
    }
    
    private String extractVisibility(ConstructorDeclaration constructor) {
        return constructor.getAccessSpecifier().asString();
    }
    
    private String extractVisibility(FieldDeclaration field) {
        return field.getAccessSpecifier().asString();
    }
}
