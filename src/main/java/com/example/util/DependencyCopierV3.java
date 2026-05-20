package com.example.util;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Java文件依赖分析与复制工具类（V3 增强版本）
 * 基于MavenDependencyCopier改进
 * 
 * 功能特点：
 * 1. 递归扫描所有Java文件，构建类名映射
 * 2. 解析import语句和同包类引用
 * 3. 支持简单类名和全限定类名查找
 * 4. 自动处理同名文件
 * 5. 避免重复处理
 */
public class DependencyCopierV3 {
    
    private final Path sourcePath;
    private final Path targetPath;
    private final Set<String> processedFiles = new HashSet<>();
    private final Map<String, List<Path>> classNameToPaths = new HashMap<>();
    private final Set<String> standardLibPrefixes = new HashSet<>(Arrays.asList(
        "java.", "javax.", "sun.", "com.sun.", "org.w3c.", "org.xml.",
        "jdk.", "javadoc.", "android.", "androidx.", "org.apache.", 
        "com.google.", "org.springframework.", "lombok.", "org.slf4j.", 
        "org.junit.", "com.fasterxml.", "android.support."
    ));
    
    public DependencyCopierV3(String sourcePath, String targetPath) {
        this.sourcePath = Paths.get(sourcePath);
        this.targetPath = Paths.get(targetPath);
    }
    
    /**
     * 复制指定类的所有依赖文件
     * @param className 简单类名（如 "StringUtil"）或全限定类名（如 "com.example.StringUtil"）
     */
    public void copyDependencies(String className) throws IOException {
        // 确保目标目录存在
        if (!Files.exists(targetPath)) {
            Files.createDirectories(targetPath);
        }
        
        // 构建类名到文件路径的映射
        buildClassNameMapping(sourcePath);
        
        // 查找并处理初始类文件
        List<Path> initialFiles = findClassFiles(className);
        if (initialFiles.isEmpty()) {
            System.out.println("未找到类文件: " + className);
            return;
        }
        
        // 处理所有依赖
        for (Path file : initialFiles) {
            processFile(file);
        }
        
        System.out.println("复制完成! 共复制 " + processedFiles.size() + " 个文件到 " + targetPath);
    }
    
    /**
     * 复制指定文件及其所有依赖
     * @param filePath 源文件路径
     */
    public void copyFromFile(String filePath) throws IOException {
        Path file = Paths.get(filePath).toAbsolutePath().normalize();
        if (!Files.exists(file)) {
            throw new FileNotFoundException("源文件不存在: " + file);
        }
        
        if (!Files.exists(targetPath)) {
            Files.createDirectories(targetPath);
        }
        
        // 构建类名映射
        buildClassNameMapping(sourcePath);
        
        // 处理文件及其依赖
        processFile(file);
        
        System.out.println("复制完成! 共复制 " + processedFiles.size() + " 个文件到 " + targetPath);
    }
    
    /**
     * 递归构建类名到文件路径的映射
     */
    private void buildClassNameMapping(Path rootPath) throws IOException {
        Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    String className = extractClassName(file, rootPath);
                    classNameToPaths.computeIfAbsent(className, k -> new ArrayList<>()).add(file);
                    
                    // 同时添加简单类名到映射中
                    String simpleName = getSimpleClassName(className);
                    if (!simpleName.equals(className)) {
                        classNameToPaths.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (name.equals("target") || name.equals("build") || name.equals(".git") 
                    || name.equals("node_modules") || name.equals(".idea")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
    
    /**
     * 从文件路径中提取全限定类名
     */
    private String extractClassName(Path file, Path rootPath) {
        String relativePath = rootPath.relativize(file).toString();
        String className = relativePath
            .replace(File.separatorChar, '.')
            .replace(".java", "");
        return className;
    }
    
    /**
     * 获取简单类名
     */
    private String getSimpleClassName(String fullClassName) {
        int lastDot = fullClassName.lastIndexOf('.');
        return lastDot >= 0 ? fullClassName.substring(lastDot + 1) : fullClassName;
    }
    
    /**
     * 根据类名查找文件
     */
    private List<Path> findClassFiles(String className) {
        List<Path> result = new ArrayList<>();
        
        // 先尝试直接查找
        if (classNameToPaths.containsKey(className)) {
            result.addAll(classNameToPaths.get(className));
        }
        
        // 如果没有找到，尝试将点替换为路径分隔符再查找
        if (result.isEmpty() && className.contains(".")) {
            String pathName = className.replace('.', File.separatorChar) + ".java";
            Path potentialPath = sourcePath.resolve(pathName);
            if (Files.exists(potentialPath)) {
                result.add(potentialPath);
            }
        }
        
        return result;
    }
    
    /**
     * 处理单个文件，复制并递归处理其依赖
     */
    private void processFile(Path file) throws IOException {
        if (processedFiles.contains(file.toString())) {
            return;
        }
        
        // 复制文件到目标目录
        String className = extractClassName(file, sourcePath);
        Path targetFile = targetPath.resolve(className.replace('.', File.separatorChar) + ".java");
        
        Files.createDirectories(targetFile.getParent());
        Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
        processedFiles.add(file.toString());
        
        System.out.println("已复制: " + sourcePath.relativize(file));
        
        // 解析依赖并递归处理
        Set<String> dependencies = findDependencies(file);
        for (String dependency : dependencies) {
            List<Path> dependencyFiles = findClassFiles(dependency);
            for (Path dependencyFile : dependencyFiles) {
                if (!processedFiles.contains(dependencyFile.toString())) {
                    processFile(dependencyFile);
                }
            }
        }
        
        // 复制内部类文件
        copyNestedClasses(file);
    }
    
    /**
     * 复制内部类/嵌套类文件
     */
    private void copyNestedClasses(Path sourceFile) throws IOException {
        Path parentDir = sourceFile.getParent();
        String baseName = sourceFile.getFileName().toString().replace(".java", "");
        
        if (parentDir != null && Files.isDirectory(parentDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(parentDir, baseName + "$*.java")) {
                for (Path nested : stream) {
                    if (!processedFiles.contains(nested.toString())) {
                        String nestedClassName = extractClassName(nested, sourcePath);
                        Path targetNested = targetPath.resolve(nestedClassName.replace('.', File.separatorChar) + ".java");
                        Files.createDirectories(targetNested.getParent());
                        Files.copy(nested, targetNested, StandardCopyOption.REPLACE_EXISTING);
                        processedFiles.add(nested.toString());
                        System.out.println("已复制内部类: " + sourcePath.relativize(nested));
                    }
                }
            }
        }
    }
    
    /**
     * 解析Java文件中的依赖（import语句和同包类引用）
     */
    private Set<String> findDependencies(Path javaFile) throws IOException {
        Set<String> dependencies = new HashSet<>();
        String content = new String(Files.readAllBytes(javaFile));
        String cleaned = removeStringsAndComments(content);
        
        // 匹配import语句
        Pattern importPattern = Pattern.compile("^\\s*import\\s+(static\\s+)?([^;]+);", Pattern.MULTILINE);
        Matcher importMatcher = importPattern.matcher(cleaned);
        while (importMatcher.find()) {
            String importName = importMatcher.group(2).trim();
            if (!importName.endsWith(".*")) {
                dependencies.add(importName);
            }
        }
        
        // 匹配同包内使用的类
        String packageName = parsePackage(cleaned);
        if (packageName != null) {
            Set<String> samePackageClasses = findSamePackageClasses(cleaned);
            for (String cls : samePackageClasses) {
                dependencies.add(packageName + "." + cls);
            }
        }
        
        // 匹配全限定类名直接使用
        Pattern fqPattern = Pattern.compile("\\b([a-z][a-zA-Z0-9_]*(?:\\.[a-z][a-zA-Z0-9_]*)+\\.[A-Z][a-zA-Z0-9_]*)\\b");
        Matcher fqMatcher = fqPattern.matcher(cleaned);
        while (fqMatcher.find()) {
            dependencies.add(fqMatcher.group(1));
        }
        
        // 过滤标准库
        dependencies.removeIf(this::isStandardLibrary);
        
        return dependencies;
    }
    
    /**
     * 判断是否标准库
     */
    private boolean isStandardLibrary(String className) {
        for (String prefix : standardLibPrefixes) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }
    
    /**
     * 移除字符串和注释
     */
    private String removeStringsAndComments(String content) {
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
     * 解析package声明
     */
    private String parsePackage(String content) {
        Pattern pattern = Pattern.compile("^\\s*package\\s+([^;]+);", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
    
    /**
     * 查找同包内使用的类
     */
    private Set<String> findSamePackageClasses(String cleanedContent) {
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
                String cls = m.group(1);
                // 排除标准库类
                if (!isStandardClassName(cls)) {
                    classes.add(cls);
                }
            }
        }
        
        return classes;
    }
    
    /**
     * 判断是否是标准库类名
     */
    private boolean isStandardClassName(String simpleName) {
        Set<String> standardNames = new HashSet<>(Arrays.asList(
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character",
            "Object", "Class", "System", "Runtime", "Thread", "Runnable", "Exception", "Error",
            "StringBuilder", "StringBuffer", "Math", "Process", "ProcessBuilder",
            "Void", "Enum", "Override", "Deprecated", "SuppressWarnings",
            "Iterable", "Collection", "List", "ArrayList", "LinkedList",
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
        return standardNames.contains(simpleName);
    }
    
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("用法: java DependencyCopierV3 <源路径> <目标路径> <类名或文件路径>");
            System.out.println("示例: java DependencyCopierV3 /path/to/src /tmp/output com.example.Main");
            System.out.println("      java DependencyCopierV3 /path/to/src /tmp/output /path/to/Main.java");
            return;
        }
        
        String sourcePath = args[0];
        String targetPath = args[1];
        String target = args[2];
        
        try {
            DependencyCopierV3 copier = new DependencyCopierV3(sourcePath, targetPath);
            
            // 判断是类名还是文件路径
            if (target.endsWith(".java") || target.contains("/")) {
                copier.copyFromFile(target);
            } else {
                copier.copyDependencies(target);
            }
        } catch (IOException e) {
            System.err.println("处理过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
