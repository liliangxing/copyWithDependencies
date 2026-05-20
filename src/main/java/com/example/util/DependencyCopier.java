package com.example.util;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Java文件依赖分析与复制工具类
 * 
 * 功能：
 * 1. 分析Java文件的import语句，找出依赖的其他Java类文件
 * 2. 按原目录结构将源文件及其依赖复制到目标目录
 * 
 * 使用方法：
 * java com.example.util.DependencyCopier /path/to/YourClass.java /tmp/output /path/to/project/src/main/java
 */
public class DependencyCopier {

    // 匹配import语句的正则表达式
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
        "^\\s*import\\s+(static\\s+)?([a-zA-Z0-9_]+\\.)*([a-zA-Z0-9_]+)(\\.\\*)?\\s*;",
        Pattern.MULTILINE
    );

    // Java标准库包名前缀
    private static final Set<String> STANDARD_LIB_PREFIXES = new HashSet<>(Arrays.asList(
        "java.", "javax.", "sun.", "com.sun.", "org.w3c.", "org.xml.",
        "jdk.", "javadoc."
    ));

    /**
     * 主方法：命令行入口
     * 
     * @param args 参数：[源Java文件路径] [目标输出目录] [源代码根目录]
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("用法: java DependencyCopier <源Java文件> <目标输出目录> <源代码根目录>");
            System.err.println("示例: java DependencyCopier src/main/java/com/example/Main.java /tmp/output src/main/java");
            System.exit(1);
        }

        String sourceFile = args[0];
        String outputDir = args[1];
        String sourceRoot = args[2];

        try {
            copyWithDependencies(sourceFile, outputDir, sourceRoot);
            System.out.println("复制完成！");
        } catch (Exception e) {
            System.err.println("复制失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 复制Java文件及其所有依赖到目标目录
     * 
     * @param sourceFilePath 源Java文件路径（绝对路径或相对路径）
     * @param outputDirPath  目标输出目录路径
     * @param sourceRootPath 源代码根目录路径（用于计算相对包路径）
     * @throws IOException 当文件读写失败时抛出
     */
    public static void copyWithDependencies(String sourceFilePath, String outputDirPath, String sourceRootPath) throws IOException {
        Path sourceFile = Paths.get(sourceFilePath).toAbsolutePath().normalize();
        Path outputDir = Paths.get(outputDirPath).toAbsolutePath().normalize();
        Path sourceRoot = Paths.get(sourceRootPath).toAbsolutePath().normalize();

        if (!Files.exists(sourceFile)) {
            throw new FileNotFoundException("源文件不存在: " + sourceFile);
        }

        if (!Files.isDirectory(sourceRoot)) {
            throw new FileNotFoundException("源代码根目录不存在: " + sourceRoot);
        }

        // 创建输出目录
        Files.createDirectories(outputDir);

        // 收集所有需要复制的文件
        Set<Path> filesToCopy = new LinkedHashSet<>();
        collectDependencies(sourceFile, sourceRoot, filesToCopy);

        // 复制文件
        for (Path file : filesToCopy) {
            copyFilePreservingStructure(file, sourceRoot, outputDir);
        }

        System.out.println("共复制 " + filesToCopy.size() + " 个文件到 " + outputDir);
    }

    /**
     * 递归收集文件及其依赖
     * 
     * @param file       当前处理的Java文件
     * @param sourceRoot 源代码根目录
     * @param collected  已收集的文件集合（用于去重）
     * @throws IOException 当文件读写失败时抛出
     */
    private static void collectDependencies(Path file, Path sourceRoot, Set<Path> collected) throws IOException {
        if (collected.contains(file)) {
            return; // 避免重复处理
        }

        collected.add(file);

        // 读取文件内容
        String content = new String(Files.readAllBytes(file));

        // 提取import的类
        Set<String> importedClasses = extractImports(content);

        // 查找import对应的Java文件
        for (String importedClass : importedClasses) {
            Path importedFile = findClassFile(importedClass, sourceRoot);
            if (importedFile != null && !collected.contains(importedFile)) {
                collectDependencies(importedFile, sourceRoot, collected);
            }
        }

        // 处理内部类引用的外部类（同一文件中的类）
        // 这里简化处理，只处理显式import的类
    }

    /**
     * 从Java源文件中提取import语句
     * 
     * @param content Java源文件内容
     * @return 导入的类名集合
     */
    private static Set<String> extractImports(String content) {
        Set<String> imports = new HashSet<>();
        Matcher matcher = IMPORT_PATTERN.matcher(content);

        while (matcher.find()) {
            String fullImport = matcher.group(2); // 获取完整的包名+类名
            if (fullImport != null) {
                String className = fullImport + matcher.group(3); // 类名
                imports.add(className);
            }
        }

        return imports;
    }

    /**
     * 根据类名查找对应的Java文件
     * 
     * @param className  完整类名（如：com.example.util.Helper）
     * @param sourceRoot 源代码根目录
     * @return Java文件路径，如果找不到则返回null
     */
    private static Path findClassFile(String className, Path sourceRoot) {
        // 将类名转换为文件路径
        String relativePath = className.replace('.', File.separatorChar) + ".java";
        Path candidate = sourceRoot.resolve(relativePath);

        if (Files.exists(candidate)) {
            return candidate;
        }

        // 尝试查找内部类（$符号）
        String internalClassPath = className.replace('$', File.separatorChar).replace('.', File.separatorChar) + ".java";
        Path internalCandidate = sourceRoot.resolve(internalClassPath);
        if (Files.exists(internalCandidate)) {
            return internalCandidate;
        }

        return null;
    }

    /**
     * 复制文件并保持目录结构
     * 
     * @param sourceFile 源文件
     * @param sourceRoot 源代码根目录
     * @param outputDir  目标输出目录
     * @throws IOException 当文件复制失败时抛出
     */
    private static void copyFilePreservingStructure(Path sourceFile, Path sourceRoot, Path outputDir) throws IOException {
        // 计算相对于sourceRoot的路径
        Path relativePath = sourceRoot.relativize(sourceFile);
        Path targetPath = outputDir.resolve(relativePath);

        // 创建目标目录
        Files.createDirectories(targetPath.getParent());

        // 复制文件
        Files.copy(sourceFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("已复制: " + relativePath);
    }

    /**
     * 判断是否为标准库类
     * 
     * @param className 类名
     * @return 如果是标准库类返回true，否则返回false
     */
    private static boolean isStandardLibrary(String className) {
        for (String prefix : STANDARD_LIB_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取项目中所有Java文件
     * 
     * @param sourceRoot 源代码根目录
     * @return 所有Java文件的路径集合
     * @throws IOException 当遍历目录失败时抛出
     */
    public static Set<Path> findAllJavaFiles(Path sourceRoot) throws IOException {
        Set<Path> javaFiles = new HashSet<>();
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".java")) {
                    javaFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return javaFiles;
    }
}
