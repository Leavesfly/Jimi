package io.leavesfly.jimi.adk.knowledge.graph.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.MethodCallExpr;

import io.leavesfly.jimi.adk.knowledge.graph.model.CodeEntity;
import io.leavesfly.jimi.adk.knowledge.graph.model.CodeRelation;
import io.leavesfly.jimi.adk.knowledge.graph.model.EntityType;
import io.leavesfly.jimi.adk.knowledge.graph.model.RelationType;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Java AST 解析器
 * <p>
 * 使用 JavaParser 解析 Java 源代码，提取代码实体和关系
 */
@Slf4j
public class JavaASTParser {
    
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
    public io.leavesfly.jimi.adk.knowledge.graph.parser.ParseResult parseFile(Path filePath, Path projectRoot) {
        io.leavesfly.jimi.adk.knowledge.graph.parser.ParseResult result = io.leavesfly.jimi.adk.knowledge.graph.parser.ParseResult.builder()
            .filePath(projectRoot.relativize(filePath).toString())
            .build();
        
        try {
            // 读取文件内容
            String content = Files.readString(filePath);
            
            // 解析 Java 代码
            com.github.javaparser.ParseResult<CompilationUnit> parseResult = javaParser.parse(content);
            
            if (!parseResult.isSuccessful() || !parseResult.getResult().isPresent()) {
                log.warn("Failed to parse file: {}", filePath);
                return io.leavesfly.jimi.adk.knowledge.graph.parser.ParseResult.failure(
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
            return io.leavesfly.jimi.adk.knowledge.graph.parser.ParseResult.failure(
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
                                     CodeEntity fileEntity, io.leavesfly.jimi.adk.knowledge.graph.parser.ParseResult result) {
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
                                  io.leavesfly.jimi.adk.knowledge.graph.parser.ParseResult result) {
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
                            io.leavesfly.jimi.adk.knowledge.graph.parser.ParseResult result) {
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
                            io.leavesfly.jimi.adk.knowledge.graph.parser.ParseResult result) {
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
                                  String classQualifiedName, io.leavesfly.jimi.adk.knowledge.graph.parser.ParseResult result) {
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
                           io.leavesfly.jimi.adk.knowledge.graph.parser.ParseResult result) {
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
     */
    private void parseMethodCalls(Node node, CodeEntity methodEntity, 
                                  io.leavesfly.jimi.adk.knowledge.graph.parser.ParseResult result) {
        // 查找方法调用表达式
        node.findAll(MethodCallExpr.class).forEach(methodCall -> {
            String calledMethodName = methodCall.getNameAsString();
            
            // 尝试解析调用的方法 (简化版，仅记录方法名)
            // 完整实现需要符号解析器
            methodEntity.addAttribute("calls_" + calledMethodName, true);
            
            // TODO: 添加 CALLS 关系 (需要符号解析确定目标方法的完整限定名)
        });
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
