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
 * Java方法级依赖分析与复制工具类（V2增强版 - V4）
 * 
 * 功能：仅分析指定方法体内使用到的类，复制这些类所在的文件
 * 与V2的区别：V2分析整个文件的所有依赖，V4只分析方法体内的依赖
 * 
 * 使用方法：
 * java DependencyCopierV2V4 <项目根目录> <目标文件相对路径> <方法名> <输出目录>
 */
public class DependencyCopierV2V4 {

    // 匹配 import 语句
    private static final Pattern IMPORT_PATTERN = 
        Pattern.compile("^\\s*import\\s+(static\\s+)?([^;]+);", Pattern.MULTILINE);
    
    // 匹配全限定类名
    private static final Pattern FULL_QUALIFIED_PATTERN = 
        Pattern.compile("\\b([a-z][a-zA-Z0-9_]*(?:\\.[a-z][a-zA-Z0-9_]*)+\\.[A-Z][a-zA-Z0-9_]*)\\b");
    
    // 匹配简单类名
    private static final Pattern SIMPLE_CLASS_PATTERN = 
        Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\b");
    
    // 匹配 package 声明
    private static final Pattern PACKAGE_PATTERN = 
        Pattern.compile("^\\s*package\\s+([^;]+);", Pattern.MULTILINE);

    /**
     * 分析方法级依赖并复制
     * 
     * @param projectRoot    项目根目录
     * @param targetFilePath 目标文件相对路径
     * @param targetMethod   目标方法名（必需）
     * @param outputDir      输出目录
     * @return 被复制的文件列表
     */
    public static List<File> analyzeMethodAndCopy(String projectRoot, String targetFilePath, 
                                                   String targetMethod, String outputDir) throws IOException {
        if (targetMethod == null || targetMethod.isEmpty()) {
            throw new IllegalArgumentException("方法名不能为空，V4版本必须指定方法名");
        }
        
        Path rootPath = Paths.get(projectRoot).toAbsolutePath().normalize();
        Path sourceFile = rootPath.resolve(targetFilePath).normalize();
        Path outputPath = Paths.get(outputDir).toAbsolutePath().normalize();
        
        if (!Files.exists(sourceFile)) {
            throw new FileNotFoundException("源文件不存在: " + sourceFile);
        }
        
        Files.createDirectories(outputPath);
        
        // 1. 解析目标文件内容
        String originalContent = new String(Files.readAllBytes(sourceFile), StandardCharsets.UTF_8);
        
        // 2. 提取方法体
        String methodBody = extractMethodBody(originalContent, targetMethod);
        if (methodBody == null) {
            throw new IllegalArgumentException("未找到方法: " + targetMethod);
        }
        
        // 移除字符串和注释
        String cleanedMethodBody = removeStringsAndComments(methodBody);
        String cleanedOriginal = removeStringsAndComments(originalContent);
        
        // 3. 解析import和package（从完整文件解析）
        String packageName = parsePackage(cleanedOriginal);
        Map<String, String> importMap = parseImports(cleanedOriginal);
        
        // 4. 仅从方法体中收集依赖的类
        Set<String> methodDependencies = new HashSet<>();
        
        // 从方法体中提取全限定类名
        Matcher fqMatcher = FULL_QUALIFIED_PATTERN.matcher(cleanedMethodBody);
        while (fqMatcher.find()) {
            methodDependencies.add(fqMatcher.group(1));
        }
        
        // 从方法体中提取简单类名，通过import映射
        Matcher simpleMatcher = SIMPLE_CLASS_PATTERN.matcher(cleanedMethodBody);
        while (simpleMatcher.find()) {
            String simpleName = simpleMatcher.group(1);
            if (importMap.containsKey(simpleName)) {
                methodDependencies.add(importMap.get(simpleName));
            }
        }
        
        // 同包内使用的类
        Set<String> samePackageClasses = findSamePackageClasses(cleanedMethodBody, importMap.keySet());
        for (String className : samePackageClasses) {
            if (packageName != null && !packageName.isEmpty()) {
                methodDependencies.add(packageName + "." + className);
            }
        }
        
        // 5. 过滤出项目内部类
        Set<String> projectDeps = methodDependencies.stream()
            .filter(dep -> isProjectClass(dep))
            .collect(Collectors.toSet());
        
        System.out.println("方法 '" + targetMethod + "' 使用了 " + projectDeps.size() + " 个项目内部类:");
        projectDeps.forEach(dep -> System.out.println("  - " + dep));
        
        // 6. 复制文件
        List<File> copiedFiles = new ArrayList<>();
        Set<String> processedClasses = new HashSet<>();
        
        // 先复制目标文件
        Path relativePath = rootPath.relativize(sourceFile);
        Path destFile = outputPath.resolve(relativePath);
        Files.createDirectories(destFile.getParent());
        Files.copy(sourceFile, destFile, StandardCopyOption.REPLACE_EXISTING);
        copiedFiles.add(destFile.toFile());
        
        // 复制依赖文件（不递归传递依赖，仅直接依赖）
        for (String className : projectDeps) {
            copyClassFile(projectRoot, className, outputPath, rootPath, copiedFiles, processedClasses);
        }
        
        System.out.println("复制完成，共复制 " + copiedFiles.size() + " 个文件到 " + outputPath);
        return copiedFiles;
    }

    /**
     * 复制单个类文件（不递归分析传递依赖）
     */
    private static void copyClassFile(String projectRoot, String fullClassName, Path outputPath,
                                       Path rootPath, List<File> copiedFiles, Set<String> processed) 
                                       throws IOException {
        if (processed.contains(fullClassName)) {
            return;
        }
        processed.add(fullClassName);
        
        String relativePath = fullClassName.replace('.', File.separatorChar) + ".java";
        Path sourceFile = findSourceFile(projectRoot, relativePath);
        
        if (sourceFile == null || !Files.exists(sourceFile)) {
            System.err.println("  未找到源文件: " + fullClassName + " (跳过)");
            return;
        }
        
        Path destFile = outputPath.resolve(rootPath.relativize(sourceFile));
        if (!Files.exists(destFile)) {
            Files.createDirectories(destFile.getParent());
            Files.copy(sourceFile, destFile, StandardCopyOption.REPLACE_EXISTING);
            copiedFiles.add(destFile.toFile());
            System.out.println("  复制: " + rootPath.relativize(sourceFile));
        }
        
        copyNestedClasses(sourceFile, outputPath, rootPath, copiedFiles);
    }

    /**
     * 提取方法体（包括构造函数）
     */
    private static String extractMethodBody(String content, String methodName) {
        // 处理构造函数（<init>或类名）
        String actualMethodName = methodName;
        String constructorPattern = null;
        
        // 如果是<init>，尝试匹配任何构造函数
        if ("<init>".equals(methodName)) {
            // 匹配构造函数: public ClassName(...) {
            Pattern classPattern = Pattern.compile("public\\s+class\\s+(\\w+)");
            Matcher classMatcher = classPattern.matcher(content);
            if (classMatcher.find()) {
                actualMethodName = classMatcher.group(1);
            }
        }
        
        String methodPattern = 
            "(?:(?:public|private|protected|static|final|native|synchronized|abstract|transient)\\s+)*" +
            "[\\w<>\\[\\],\\.\\s]+\\s+" + Pattern.quote(actualMethodName) + "\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w\\.,\\s]+)?\\s*\\{";
        
        Pattern pattern = Pattern.compile(methodPattern);
        Matcher matcher = pattern.matcher(content);
        
        if (matcher.find()) {
            int braceStart = content.indexOf('{', matcher.start());
            if (braceStart == -1) return null;
            
            int braceCount = 1;
            int i = braceStart + 1;
            while (i < content.length() && braceCount > 0) {
                char c = content.charAt(i);
                if (c == '{') braceCount++;
                else if (c == '}') braceCount--;
                
                if (c == '"' || c == '\'') {
                    char quote = c;
                    i++;
                    while (i < content.length() && content.charAt(i) != quote) {
                        if (content.charAt(i) == '\\') i++;
                        i++;
                    }
                } else if (c == '/' && i + 1 < content.length()) {
                    if (content.charAt(i + 1) == '/') {
                        while (i < content.length() && content.charAt(i) != '\n') i++;
                    } else if (content.charAt(i + 1) == '*') {
                        i += 2;
                        while (i + 1 < content.length() && !(content.charAt(i) == '*' && content.charAt(i+1) == '/')) i++;
                        i++;
                    }
                }
                i++;
            }
            return content.substring(matcher.start(), i);
        }
        return null;
    }

    /**
     * 移除字符串和注释
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

    private static String parsePackage(String content) {
        Matcher matcher = PACKAGE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

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

    private static Set<String> findSamePackageClasses(String cleanedContent, Set<String> importedNames) {
        Set<String> classes = new HashSet<>();
        
        Pattern[] patterns = {
            Pattern.compile("\\bnew\\s+([A-Z][a-zA-Z0-9_]*)\\s*\\("),
            Pattern.compile("\\bextends\\s+([A-Z][a-zA-Z0-9_]*)"),
            Pattern.compile("\\bimplements\\s+([A-Z][a-zA-Z0-9_]*(?:\\s*,\\s*[A-Z][a-zA-Z0-9_]*)*)"),
            Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\s+[a-z][a-zA-Z0-9_]*\\s*[;=,)]"),
            Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\.[A-Z_][a-zA-Z0-9_]*\\b"),
            Pattern.compile("<\\s*([A-Z][a-zA-Z0-9_]*)\\s*>"),
            Pattern.compile("\\(\\s*([A-Z][a-zA-Z0-9_]*)\\s+"),
            Pattern.compile("\\breturn\\s+([A-Z][a-zA-Z0-9_]*)\\s*[;(]")
        };
        
        for (Pattern p : patterns) {
            Matcher m = p.matcher(cleanedContent);
            while (m.find()) {
                String match = m.group(1);
                if (!importedNames.contains(match) && !isJavaStandardClass(match)) {
                    classes.add(match);
                }
            }
        }
        
        return classes;
    }

    private static boolean isJavaStandardClass(String simpleName) {
        Set<String> standardClasses = new HashSet<>(Arrays.asList(
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
        return standardClasses.contains(simpleName);
    }

    private static boolean isProjectClass(String fullClassName) {
        if (fullClassName == null || fullClassName.isEmpty()) return false;
        
        if (fullClassName.startsWith("java.") || fullClassName.startsWith("javax.") ||
            fullClassName.startsWith("sun.") || fullClassName.startsWith("com.sun.") ||
            fullClassName.startsWith("jdk.") || fullClassName.startsWith("org.w3c.") ||
            fullClassName.startsWith("org.xml.") || fullClassName.startsWith("org.omg.")) {
            return false;
        }
        
        if (fullClassName.startsWith("org.apache.") || fullClassName.startsWith("com.google.") ||
            fullClassName.startsWith("org.springframework.") || fullClassName.startsWith("lombok.") ||
            fullClassName.startsWith("org.slf4j.") || fullClassName.startsWith("org.junit.") ||
            fullClassName.startsWith("com.fasterxml.") || fullClassName.startsWith("android.") ||
            fullClassName.startsWith("androidx.") || fullClassName.startsWith("android.support.")) {
            return false;
        }
        
        return true;
    }

    private static Path findSourceFile(String projectRoot, String relativePath) throws IOException {
        Path root = Paths.get(projectRoot);
        Path direct = root.resolve(relativePath);
        if (Files.exists(direct)) return direct;
        
        List<String> sourceDirs = Arrays.asList("src/main/java", "src/test/java", "src");
        for (String dir : sourceDirs) {
            Path candidate = root.resolve(dir).resolve(relativePath);
            if (Files.exists(candidate)) return candidate;
        }
        
        final Path[] found = {null};
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
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
        
        return found[0];
    }

    private static void copyNestedClasses(Path sourceFile, Path outputPath, Path rootPath, 
                                           List<File> copiedFiles) throws IOException {
        Path parentDir = sourceFile.getParent();
        String baseName = sourceFile.getFileName().toString().replace(".java", "");
        
        if (parentDir != null && Files.isDirectory(parentDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(parentDir, baseName + "$*.java")) {
                for (Path nested : stream) {
                    Path destNested = outputPath.resolve(rootPath.relativize(nested));
                    if (!Files.exists(destNested)) {
                        Files.createDirectories(destNested.getParent());
                        Files.copy(nested, destNested, StandardCopyOption.REPLACE_EXISTING);
                        copiedFiles.add(destNested.toFile());
                        System.out.println("  复制内部类: " + rootPath.relativize(nested));
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("用法: java DependencyCopierV2V4 <项目根目录> <目标文件相对路径> <方法名> <输出目录>");
            System.err.println("示例: java DependencyCopierV2V4 /home/user/myproject src/main/java/com/example/Main.java myMethod /tmp/output");
            System.exit(1);
        }
        
        try {
            String projectRoot = args[0];
            String targetFile = args[1];
            String targetMethod = args[2];
            String outputDir = args[3];
            
            List<File> copied = analyzeMethodAndCopy(projectRoot, targetFile, targetMethod, outputDir);
            
            System.out.println("\n成功复制以下文件：");
            copied.forEach(f -> System.out.println("  " + f.getAbsolutePath()));
            
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
