package com.example.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Java文件依赖分析与复制工具类（V1 增强版）
 * 
 * 功能：
 * 1. 分析Java文件的import语句和同包类引用，找出依赖的其他Java类文件
 * 2. 递归分析传递依赖
 * 3. 按原目录结构将源文件及其所有依赖复制到目标目录
 * 
 * 使用方法：
 * java com.example.util.DependencyCopierV1 /path/to/YourClass.java /tmp/output /path/to/project/src/main/java
 */
public class DependencyCopierV1 {

    // 匹配import语句的正则表达式
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
        "^\\s*import\\s+(static\\s+)?([^;]+);", Pattern.MULTILINE
    );

    // 匹配全限定类名（如 com.example.ClassName）
    private static final Pattern FULL_QUALIFIED_PATTERN = 
        Pattern.compile("\\b([a-z][a-zA-Z0-9_]*(?:\\.[a-z][a-zA-Z0-9_]*)+\\.[A-Z][a-zA-Z0-9_]*)\\b");
    
    // 匹配简单类名
    private static final Pattern SIMPLE_CLASS_PATTERN = 
        Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\b");
    
    // 匹配 package 声明
    private static final Pattern PACKAGE_PATTERN = 
        Pattern.compile("^\\s*package\\s+([^;]+);", Pattern.MULTILINE);

    // Java/Android标准库包名前缀
    private static final Set<String> STANDARD_LIB_PREFIXES = new HashSet<>(Arrays.asList(
        "java.", "javax.", "sun.", "com.sun.", "org.w3c.", "org.xml.",
        "jdk.", "javadoc.", "android.", "androidx."
    ));

    // 常见标准库类名
    private static final Set<String> STANDARD_CLASS_NAMES = new HashSet<>(Arrays.asList(
        "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character",
        "Object", "Class", "System", "Runtime", "Thread", "Runnable", "Exception", "Error",
        "StringBuilder", "StringBuffer", "Math", "Process", "ProcessBuilder",
        "Void", "Enum", "Override", "Deprecated", "SuppressWarnings", "SafeVarargs",
        "FunctionalInterface", "Iterable", "Collection", "List", "ArrayList", "LinkedList",
        "Set", "HashSet", "TreeSet", "Map", "HashMap", "TreeMap", "LinkedHashMap",
        "Queue", "Deque", "ArrayDeque", "PriorityQueue", "Stack", "Vector", "Hashtable",
        "Date", "Calendar", "TimeZone", "Locale", "UUID", "Random", "Scanner",
        "File", "Path", "Paths", "Files", "Charset", "StandardCharsets",
        "InputStream", "OutputStream", "Reader", "Writer", "BufferedReader", "BufferedWriter",
        "IOException", "FileNotFoundException", "RuntimeException", "IllegalArgumentException",
        "NullPointerException", "IllegalStateException",
        "Optional", "Stream", "Collectors", "Comparator", "Predicate", "Function", "Consumer",
        "Pattern", "Matcher", "BigDecimal", "BigInteger",
        "Activity", "View", "ViewGroup", "Context", "Intent", "Bundle", "Toast",
        "TextView", "ImageView", "Button", "ImageButton", "LinearLayout", "RelativeLayout",
        "FrameLayout", "RecyclerView", "Adapter", "ViewHolder", "LayoutInflater",
        "Menu", "MenuItem", "Toolbar", "ActionBar", "FragmentManager", "Fragment",
        "SharedPreferences", "Editor", "PackageManager", "Application", "Service",
        "BroadcastReceiver", "ContentResolver", "Uri", "Cursor", "SQLiteOpenHelper",
        "SQLiteDatabase", "ContentValues", "Handler", "Looper", "Message",
        "AsyncTask", "AlertDialog", "Dialog", "Window", "WindowManager",
        "DisplayMetrics", "Animation", "Bitmap", "Drawable", "Color", "Paint", "Canvas", "Rect",
        "OnClickListener", "OnLongClickListener", "OnTouchListener", "OnScrollListener",
        "LayoutParams", "OnGlobalLayoutListener",
        "InterruptedException", "Comparable", "CharSequence", "Cloneable", "Serializable"
    ));

    /**
     * 主方法：命令行入口
     * 
     * @param args 参数：[源Java文件路径] [目标输出目录] [源代码根目录]
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("用法: java DependencyCopierV1 <源Java文件> <目标输出目录> <源代码根目录>");
            System.err.println("示例: java DependencyCopierV1 src/main/java/com/example/Main.java /tmp/output src/main/java");
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
        Set<String> processedClasses = new HashSet<>();
        
        // 先复制目标文件
        filesToCopy.add(sourceFile);
        
        // 递归收集依赖
        collectDependencies(sourceFile, sourceRoot, filesToCopy, processedClasses);

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
     * @param collected  已收集的文件集合
     * @param processed  已处理的类名集合（防止循环依赖）
     * @throws IOException 当文件读写失败时抛出
     */
    private static void collectDependencies(Path file, Path sourceRoot, Set<Path> collected, Set<String> processed) throws IOException {
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        
        // 移除字符串和注释，避免误匹配
        String cleaned = removeStringsAndComments(content);
        
        // 解析package
        String packageName = parsePackage(cleaned);
        
        // 解析import
        Map<String, String> importMap = parseImports(cleaned);
        
        // 收集依赖的类名
        Set<String> dependencies = new HashSet<>();
        
        // 1. 从import中收集
        dependencies.addAll(importMap.values());
        
        // 2. 从代码中直接使用的全限定名收集
        Matcher fqMatcher = FULL_QUALIFIED_PATTERN.matcher(cleaned);
        while (fqMatcher.find()) {
            dependencies.add(fqMatcher.group(1));
        }
        
        // 3. 从简单类名通过import映射收集
        Matcher simpleMatcher = SIMPLE_CLASS_PATTERN.matcher(cleaned);
        while (simpleMatcher.find()) {
            String simpleName = simpleMatcher.group(1);
            if (importMap.containsKey(simpleName)) {
                dependencies.add(importMap.get(simpleName));
            }
        }
        
        // 4. 收集同包内直接使用的类
        Set<String> samePackageClasses = findSamePackageClasses(cleaned, importMap.keySet());
        for (String className : samePackageClasses) {
            if (packageName != null && !packageName.isEmpty()) {
                dependencies.add(packageName + "." + className);
            }
        }
        
        // 过滤出项目内部类
        for (String className : dependencies) {
            if (isProjectClass(className) && !processed.contains(className)) {
                processed.add(className);
                Path classFile = findClassFile(className, sourceRoot);
                if (classFile != null && !collected.contains(classFile)) {
                    collected.add(classFile);
                    // 递归分析这个文件的依赖
                    collectDependencies(classFile, sourceRoot, collected, processed);
                }
            }
        }
    }

    /**
     * 移除字符串字面量和注释
     */
    private static String removeStringsAndComments(String content) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        boolean inSingleLineComment = false;
        boolean inMultiLineComment = false;
        boolean inString = false;
        boolean inChar = false;
        
        while (i < content.length()) {
            char c = content.charAt(i);
            
            if (!inString && !inChar && !inMultiLineComment && c == '/' && i + 1 < content.length() && content.charAt(i + 1) == '/') {
                inSingleLineComment = true;
                i += 2;
                continue;
            }
            
            if (!inString && !inChar && !inMultiLineComment && c == '/' && i + 1 < content.length() && content.charAt(i + 1) == '*') {
                inMultiLineComment = true;
                i += 2;
                continue;
            }
            
            if (inMultiLineComment && c == '*' && i + 1 < content.length() && content.charAt(i + 1) == '/') {
                inMultiLineComment = false;
                i += 2;
                continue;
            }
            
            if (inSingleLineComment || inMultiLineComment) {
                if (c == '\n') inSingleLineComment = false;
                i++;
                continue;
            }
            
            if (!inChar && c == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                inString = !inString;
                i++;
                continue;
            }
            
            if (!inString && c == '\'' && (i == 0 || content.charAt(i - 1) != '\\')) {
                inChar = !inChar;
                i++;
                continue;
            }
            
            if (!inString && !inChar) {
                result.append(c);
            }
            
            i++;
        }
        
        return result.toString();
    }

    /**
     * 解析 package 声明
     */
    private static String parsePackage(String content) {
        Matcher matcher = PACKAGE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * 解析 import 语句
     */
    private static Map<String, String> parseImports(String content) {
        Map<String, String> imports = new HashMap<>();
        Matcher matcher = IMPORT_PATTERN.matcher(content);
        
        while (matcher.find()) {
            String fullName = matcher.group(2).trim();
            if (fullName.endsWith(".*")) continue;
            
            int lastDot = fullName.lastIndexOf('.');
            String simpleName = lastDot > 0 ? fullName.substring(lastDot + 1) : fullName;
            imports.put(simpleName, fullName);
        }
        
        return imports;
    }

    /**
     * 查找同包内直接使用的类
     */
    private static Set<String> findSamePackageClasses(String cleanedContent, Set<String> importedNames) {
        Set<String> classes = new HashSet<>();
        
        Pattern[] patterns = {
            Pattern.compile("\\bnew\\s+([A-Z][a-zA-Z0-9_]*)\\s*\\("),
            Pattern.compile("\\bextends\\s+([A-Z][a-zA-Z0-9_]*)"),
            Pattern.compile("\\bimplements\\s+([A-Z][a-zA-Z0-9_]*(?:\\s*,\\s*[A-Z][a-zA-Z0-9_]*)*)"),
            Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\s+[a-z][a-zA-Z0-9_]*\\s*[;=,)]"),
            Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\.[A-Z_][a-zA-Z0-9_]*\\b")
        };
        
        for (Pattern p : patterns) {
            Matcher m = p.matcher(cleanedContent);
            while (m.find()) {
                String match = m.group(1);
                if (!importedNames.contains(match) && !STANDARD_CLASS_NAMES.contains(match)) {
                    classes.add(match);
                }
            }
        }
        
        return classes;
    }

    /**
     * 根据类名查找对应的Java文件
     */
    private static Path findClassFile(String className, Path sourceRoot) {
        String relativePath = className.replace('.', File.separatorChar) + ".java";
        Path candidate = sourceRoot.resolve(relativePath);
        if (Files.exists(candidate)) return candidate;
        
        // 尝试在常见源码目录下查找
        List<String> sourceDirs = Arrays.asList("src/main/java", "src/test/java", "src");
        for (String dir : sourceDirs) {
            Path p = sourceRoot.resolve(dir).resolve(relativePath);
            if (Files.exists(p)) return p;
        }
        
        // 兜底搜索
        final Path[] found = {null};
        try {
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().equals(Paths.get(relativePath).getFileName().toString())) {
                        found[0] = file;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.equals("target") || name.equals("build") || name.equals(".git") 
                        || name.equals("node_modules")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // ignore
        }
        
        return found[0];
    }

    /**
     * 复制文件并保持目录结构
     */
    private static void copyFilePreservingStructure(Path sourceFile, Path sourceRoot, Path outputDir) throws IOException {
        Path relativePath = sourceRoot.relativize(sourceFile);
        Path targetPath = outputDir.resolve(relativePath);

        Files.createDirectories(targetPath.getParent());
        Files.copy(sourceFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("已复制: " + relativePath);
        
        // 复制内部类/嵌套类文件（如 Foo$Bar.java）
        copyNestedClasses(sourceFile, sourceRoot, outputDir);
    }

    /**
     * 复制内部类/嵌套类（在同一目录下搜索包含 $ 的文件）
     */
    private static void copyNestedClasses(Path sourceFile, Path sourceRoot, Path outputDir) throws IOException {
        Path parentDir = sourceFile.getParent();
        String baseName = sourceFile.getFileName().toString().replace(".java", "");
        
        if (parentDir != null && Files.isDirectory(parentDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(parentDir, baseName + "$*.java")) {
                for (Path nested : stream) {
                    Path relativePath = sourceRoot.relativize(nested);
                    Path targetPath = outputDir.resolve(relativePath);
                    if (!Files.exists(targetPath)) {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(nested, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("已复制内部类: " + relativePath);
                    }
                }
            }
        }
    }

    /**
     * 判断是否是需要复制的项目内部类
     */
    private static boolean isProjectClass(String fullClassName) {
        if (fullClassName == null || fullClassName.isEmpty()) return false;
        
        for (String prefix : STANDARD_LIB_PREFIXES) {
            if (fullClassName.startsWith(prefix)) return false;
        }
        
        // 排除常见第三方库
        if (fullClassName.startsWith("org.apache.") || fullClassName.startsWith("com.google.") ||
            fullClassName.startsWith("org.springframework.") || fullClassName.startsWith("lombok.") ||
            fullClassName.startsWith("org.slf4j.") || fullClassName.startsWith("org.junit.") ||
            fullClassName.startsWith("com.fasterxml.")) {
            return false;
        }
        
        return true;
    }

    /**
     * 获取项目中所有Java文件
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
