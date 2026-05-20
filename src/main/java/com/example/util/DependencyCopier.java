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
 * Java 源文件依赖分析并复制工具类（Java 8 兼容）
 * 
 * 功能：分析指定 Java 文件（或其中特定方法）使用到的所有其他自定义 Java 类，
 *       并在项目源码目录中找到这些类，按原目录结构复制到目标目录。
 * 
 * 使用方法：
 *   DependencyCopier.analyzeAndCopy(
 *       "/home/user/myproject",                                    // 项目根目录
 *       "src/main/java/com/example/util/FileCopyUtil.java",        // 目标文件相对路径
 *       "copyWithDependencies",                                    // 可选：限定分析方法（null 则分析整个文件）
 *       "/tmp/output"                                              // 输出目录
 *   );
 */
public class DependencyCopier {

    // 匹配 import 语句：import com.example.SomeClass;
    private static final Pattern IMPORT_PATTERN = 
        Pattern.compile("^\\s*import\\s+(static\\s+)?([^;]+);", Pattern.MULTILINE);
    
    // 匹配全限定类名（如 com.example.ClassName）
    private static final Pattern FULL_QUALIFIED_PATTERN = 
        Pattern.compile("\\b([a-z][a-zA-Z0-9_]*(?:\\.[a-z][a-zA-Z0-9_]*)+\\.[A-Z][a-zA-Z0-9_]*)\\b");
    
    // 匹配简单类名声明（通过 import 引入的类）
    private static final Pattern SIMPLE_CLASS_PATTERN = 
        Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\b");
    
    // 匹配 package 声明
    private static final Pattern PACKAGE_PATTERN = 
        Pattern.compile("^\\s*package\\s+([^;]+);", Pattern.MULTILINE);

    /**
     * 分析并复制依赖
     * 
     * @param projectRoot      项目根目录（绝对路径）
     * @param targetFilePath   目标 Java 文件相对于项目根目录的路径
     * @param targetMethod     可选：若不为 null，只分析该方法体内的依赖；若为 null，分析整个文件
     * @param outputDir        输出目录（绝对路径）
     * @return 被复制的文件列表
     * @throws IOException 当 IO 操作失败时
     */
    public static List<File> analyzeAndCopy(String projectRoot, String targetFilePath, 
                                             String targetMethod, String outputDir) throws IOException {
        
        Path rootPath = Paths.get(projectRoot).toAbsolutePath().normalize();
        Path sourceFile = rootPath.resolve(targetFilePath).normalize();
        Path outputPath = Paths.get(outputDir).toAbsolutePath().normalize();
        
        if (!Files.exists(sourceFile)) {
            throw new FileNotFoundException("源文件不存在: " + sourceFile);
        }
        
        // 确保输出目录存在
        Files.createDirectories(outputPath);
        
        // 1. 解析目标文件内容
        String originalContent = new String(Files.readAllBytes(sourceFile), StandardCharsets.UTF_8);
        String contentForAnalysis = originalContent;
        
        // 如果指定了方法，尝试提取方法体
        String methodBody = null;
        if (targetMethod != null && !targetMethod.isEmpty()) {
            methodBody = extractMethodBody(originalContent, targetMethod);
            if (methodBody != null) {
                contentForAnalysis = methodBody;
            } else {
                System.err.println("警告：未找到方法 '" + targetMethod + "'，将分析整个文件");
            }
        }
        
        // 移除字符串字面量和注释，避免误匹配
        String cleanedContent = removeStringsAndComments(contentForAnalysis);
        String cleanedOriginal = removeStringsAndComments(originalContent);
        
        // 2. 收集所有依赖的类名（全限定名）
        Set<String> dependencies = new HashSet<>();
        
        // 解析 package（从原始文件中解析）
        String packageName = parsePackage(cleanedOriginal);
        
        // 解析 import
        Map<String, String> importMap = parseImports(cleanedOriginal); // simpleName -> fullQualifiedName
        
        // 添加 import 中的类
        dependencies.addAll(importMap.values());
        
        // 解析代码中直接使用的全限定名（如 new com.example.Util()）
        Matcher fqMatcher = FULL_QUALIFIED_PATTERN.matcher(cleanedContent);
        while (fqMatcher.find()) {
            dependencies.add(fqMatcher.group(1));
        }
        
        // 解析代码中使用的简单类名，并通过 importMap 映射为全限定名
        Matcher simpleMatcher = SIMPLE_CLASS_PATTERN.matcher(cleanedContent);
        while (simpleMatcher.find()) {
            String simpleName = simpleMatcher.group(1);
            if (importMap.containsKey(simpleName)) {
                dependencies.add(importMap.get(simpleName));
            }
        }
        
        // 3. 同一包内的类（无 import 但直接使用）
        Set<String> samePackageClasses = findSamePackageClasses(cleanedContent, packageName, importMap.keySet());
        for (String className : samePackageClasses) {
            if (packageName != null && !packageName.isEmpty()) {
                dependencies.add(packageName + "." + className);
            } else {
                dependencies.add(className);
            }
        }
        
        // 4. 过滤系统类（java.*, javax.*, sun.*, com.sun.* 等），只保留项目自定义类
        Set<String> projectDependencies = dependencies.stream()
            .filter(dep -> isProjectClass(dep))
            .collect(Collectors.toSet());
        
        System.out.println("分析完成，发现 " + projectDependencies.size() + " 个项目内部依赖:");
        projectDependencies.forEach(dep -> System.out.println("  - " + dep));
        
        // 5. 在项目中查找对应的 .java 文件并复制
        List<File> copiedFiles = new ArrayList<>();
        Set<String> processedClasses = new HashSet<>(); // 防止循环依赖导致死循环
        
        // 先复制目标文件本身
        Path relativePath = rootPath.relativize(sourceFile);
        Path destFile = outputPath.resolve(relativePath);
        Files.createDirectories(destFile.getParent());
        Files.copy(sourceFile, destFile, StandardCopyOption.REPLACE_EXISTING);
        copiedFiles.add(destFile.toFile());
        
        // 再复制依赖文件
        for (String className : projectDependencies) {
            copyClassAndNested(projectRoot, className, outputPath, rootPath, copiedFiles, processedClasses);
        }
        
        System.out.println("复制完成，共复制 " + copiedFiles.size() + " 个文件到 " + outputPath);
        return copiedFiles;
    }

    /**
     * 递归复制类及其内部类/嵌套类
     */
    private static void copyClassAndNested(String projectRoot, String fullClassName, Path outputPath,
                                           Path rootPath, List<File> copiedFiles, Set<String> processed) 
                                           throws IOException {
        if (processed.contains(fullClassName)) {
            return;
        }
        processed.add(fullClassName);
        
        // 将全限定名转为文件路径：com.example.Foo -> com/example/Foo.java
        String relativePath = fullClassName.replace('.', File.separatorChar) + ".java";
        
        // 在项目中搜索（支持多模块/多源码目录）
        Path sourceFile = findSourceFile(projectRoot, relativePath);
        
        if (sourceFile == null || !Files.exists(sourceFile)) {
            System.err.println("  未找到源文件: " + fullClassName + " (跳过)");
            return;
        }
        
        // 复制主类文件
        Path destFile = outputPath.resolve(rootPath.relativize(sourceFile));
        if (!Files.exists(destFile)) {
            Files.createDirectories(destFile.getParent());
            Files.copy(sourceFile, destFile, StandardCopyOption.REPLACE_EXISTING);
            copiedFiles.add(destFile.toFile());
            System.out.println("  复制: " + rootPath.relativize(sourceFile));
            
            // 递归分析这个文件的依赖（传递依赖）
            String newContent = new String(Files.readAllBytes(sourceFile), StandardCharsets.UTF_8);
            String cleanedNewContent = removeStringsAndComments(newContent);
            Set<String> transitiveDeps = new HashSet<>();
            
            // 解析其 import
            Map<String, String> imports = parseImports(cleanedNewContent);
            transitiveDeps.addAll(imports.values());
            
            // 解析全限定名
            Matcher m = FULL_QUALIFIED_PATTERN.matcher(cleanedNewContent);
            while (m.find()) transitiveDeps.add(m.group(1));
            
            // 解析同包类
            String pkg = parsePackage(cleanedNewContent);
            Set<String> samePkg = findSamePackageClasses(cleanedNewContent, pkg, imports.keySet());
            for (String c : samePkg) {
                if (pkg != null && !pkg.isEmpty()) transitiveDeps.add(pkg + "." + c);
                else transitiveDeps.add(c);
            }
            
            // 过滤并递归
            for (String dep : transitiveDeps) {
                if (isProjectClass(dep) && !processed.contains(dep)) {
                    copyClassAndNested(projectRoot, dep, outputPath, rootPath, copiedFiles, processed);
                }
            }
        }
        
        // 复制内部类文件（如 Foo$Bar.java 或 Foo.java 中的内部类）
        copyNestedClasses(sourceFile, outputPath, rootPath, copiedFiles);
    }

    /**
     * 复制内部类/嵌套类（在同一目录下搜索包含 $ 的文件）
     */
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

    /**
     * 在项目中查找源文件（支持 src/main/java, src/test/java 等常见源码目录）
     */
    private static Path findSourceFile(String projectRoot, String relativePath) throws IOException {
        Path root = Paths.get(projectRoot);
        
        // 常见源码目录
        List<String> sourceDirs = Arrays.asList(
            "src/main/java",
            "src/test/java",
            "src"
        );
        
        // 先尝试直接相对路径
        Path direct = root.resolve(relativePath);
        if (Files.exists(direct)) return direct;
        
        // 在各源码目录下查找
        for (String dir : sourceDirs) {
            Path candidate = root.resolve(dir).resolve(relativePath);
            if (Files.exists(candidate)) return candidate;
        }
        
        // 若未找到，遍历整个项目目录搜索
        final Path[] found = {null};
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals(Paths.get(relativePath).getFileName().toString())) {
                    // 验证包路径是否匹配
                    try {
                        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                        String pkg = parsePackage(content);
                        String expectedPkg = relativePath.substring(0, relativePath.lastIndexOf('/')).replace('/', '.');
                        if (pkg != null && expectedPkg.endsWith(pkg)) {
                            found[0] = file;
                            return FileVisitResult.TERMINATE;
                        }
                    } catch (IOException e) {
                        // ignore
                    }
                }
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // 跳过常见非源码目录以加速搜索
                String name = dir.getFileName().toString();
                if (name.equals("target") || name.equals("build") || name.equals(".git") 
                    || name.equals("node_modules") || name.equals(".idea")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        
        return found[0];
    }

    /**
     * 提取特定方法的方法体
     */
    private static String extractMethodBody(String content, String methodName) {
        // 匹配方法签名
        String methodPattern = 
            "(?:(?:public|private|protected|static|final|native|synchronized|abstract|transient)\\s+)*" +
            "[\\w<>\\[\\],\\.\\s]+\\s+" + Pattern.quote(methodName) + "\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w\\.,\\s]+)?\\s*\\{";
        
        Pattern pattern = Pattern.compile(methodPattern);
        Matcher matcher = pattern.matcher(content);
        
        if (matcher.find()) {
            int braceStart = content.indexOf('{', matcher.start());
            if (braceStart == -1) return null;
            
            // 计算匹配的括号
            int braceCount = 1;
            int i = braceStart + 1;
            while (i < content.length() && braceCount > 0) {
                char c = content.charAt(i);
                if (c == '{') braceCount++;
                else if (c == '}') braceCount--;
                
                // 跳过字符串和字符
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
     * 解析 package 声明
     */
    private static String parsePackage(String content) {
        Matcher matcher = PACKAGE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
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
            
            // 处理单行注释
            if (!inString && !inChar && !inMultiLineComment && c == '/' && i + 1 < content.length() && content.charAt(i + 1) == '/') {
                inSingleLineComment = true;
                i += 2;
                continue;
            }
            
            // 处理多行注释开始
            if (!inString && !inChar && !inMultiLineComment && c == '/' && i + 1 < content.length() && content.charAt(i + 1) == '*') {
                inMultiLineComment = true;
                i += 2;
                continue;
            }
            
            // 处理多行注释结束
            if (inMultiLineComment && c == '*' && i + 1 < content.length() && content.charAt(i + 1) == '/') {
                inMultiLineComment = false;
                i += 2;
                continue;
            }
            
            // 如果在注释中，跳过
            if (inSingleLineComment || inMultiLineComment) {
                if (c == '\n') inSingleLineComment = false;
                i++;
                continue;
            }
            
            // 处理字符串字面量
            if (!inChar && c == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                inString = !inString;
                i++;
                continue;
            }
            
            // 处理字符字面量
            if (!inString && c == '\'' && (i == 0 || content.charAt(i - 1) != '\\')) {
                inChar = !inChar;
                i++;
                continue;
            }
            
            // 如果不在字符串或注释中，添加到结果
            if (!inString && !inChar) {
                result.append(c);
            }
            
            i++;
        }
        
        return result.toString();
    }

    /**
     * 解析 import 语句，返回 Map<简单类名, 全限定类名>
     */
    private static Map<String, String> parseImports(String content) {
        Map<String, String> imports = new HashMap<>();
        Matcher matcher = IMPORT_PATTERN.matcher(content);
        
        while (matcher.find()) {
            String fullName = matcher.group(2).trim();
            // 跳过通配符 import
            if (fullName.endsWith(".*")) {
                continue;
            }
            
            // 跳过静态导入的方法或字段
            if (matcher.group(1) != null && matcher.group(1).contains("static")) {
                // 静态导入可能是方法或字段，不一定需要复制
                // 但如果是静态内部类，仍然需要
                int lastDot = fullName.lastIndexOf('.');
                if (lastDot > 0) {
                    String simpleName = fullName.substring(lastDot + 1);
                    // 静态内部类首字母大写
                    if (Character.isUpperCase(simpleName.charAt(0))) {
                        imports.put(simpleName, fullName);
                    }
                }
                continue;
            }
            
            int lastDot = fullName.lastIndexOf('.');
            String simpleName = lastDot > 0 ? fullName.substring(lastDot + 1) : fullName;
            imports.put(simpleName, fullName);
        }
        
        return imports;
    }

    /**
     * 查找同一包内直接使用的类
     */
    private static Set<String> findSamePackageClasses(String cleanedContent, String packageName, Set<String> importedNames) {
        Set<String> classes = new HashSet<>();
        
        // 匹配类使用模式
        Pattern[] patterns = {
            Pattern.compile("\\bnew\\s+([A-Z][a-zA-Z0-9_]*)\\s*\\("),  // new ClassName(
            Pattern.compile("\\bextends\\s+([A-Z][a-zA-Z0-9_]*)"),      // extends ClassName
            Pattern.compile("\\bimplements\\s+([A-Z][a-zA-Z0-9_]*(?:\\s*,\\s*[A-Z][a-zA-Z0-9_]*)*)"), // implements ClassName
            Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\s+[a-z][a-zA-Z0-9_]*\\s*[;=,)]"), // 类型声明
            Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\.[A-Z_][a-zA-Z0-9_]*\\b")  // ClassName.CONSTANT
        };
        
        for (Pattern p : patterns) {
            Matcher m = p.matcher(cleanedContent);
            while (m.find()) {
                String match = m.group(1);
                // 排除已导入的类和标准库类
                if (!importedNames.contains(match) && !isJavaStandardClass(match)) {
                    classes.add(match);
                }
            }
        }
        
        return classes;
    }

    /**
     * 判断是否属于 Java/Android 标准库类
     */
    private static boolean isJavaStandardClass(String simpleName) {
        Set<String> standardClasses = new HashSet<>(Arrays.asList(
            // Java 核心类
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character",
            "Object", "Class", "System", "Runtime", "Thread", "Runnable", "Exception", "Error",
            "StringBuilder", "StringBuffer", "Math", "StrictMath", "Process", "ProcessBuilder",
            "Void", "Enum", "Override", "Deprecated", "SuppressWarnings", "SafeVarargs",
            "FunctionalInterface", "Iterable", "Collection", "List", "ArrayList", "LinkedList",
            "Set", "HashSet", "TreeSet", "Map", "HashMap", "TreeMap", "LinkedHashMap",
            "Queue", "Deque", "ArrayDeque", "PriorityQueue", "Stack", "Vector", "Hashtable",
            "Date", "Calendar", "TimeZone", "Locale", "UUID", "Random", "Scanner",
            "File", "Path", "Paths", "Files", "Charset", "StandardCharsets",
            "InputStream", "OutputStream", "Reader", "Writer", "BufferedReader", "BufferedWriter",
            "FileInputStream", "FileOutputStream", "ByteArrayInputStream", "ByteArrayOutputStream",
            "IOException", "FileNotFoundException", "RuntimeException", "IllegalArgumentException",
            "NullPointerException", "ArrayIndexOutOfBoundsException", "IllegalStateException",
            "Optional", "Stream", "Collectors", "Comparator", "Predicate", "Function", "Consumer",
            "Pattern", "Matcher", "BigDecimal", "BigInteger", "AtomicInteger", "AtomicLong",
            "ConcurrentHashMap", "CopyOnWriteArrayList", "ExecutorService", "Executors", "Future",
            "Log", "Logger", "Level", "ConsoleHandler", "FileHandler",
            // Android 常见类
            "Activity", "View", "ViewGroup", "Context", "Intent", "Bundle", "Toast",
            "TextView", "ImageView", "Button", "ImageButton", "LinearLayout", "RelativeLayout",
            "FrameLayout", "RecyclerView", "Adapter", "ViewHolder", "LayoutInflater",
            "Menu", "MenuItem", "Toolbar", "ActionBar", "FragmentManager", "Fragment",
            "SharedPreferences", "Editor", "PackageManager", "Application", "Service",
            "BroadcastReceiver", "ContentResolver", "Uri", "Cursor", "SQLiteOpenHelper",
            "SQLiteDatabase", "ContentValues", "Handler", "Looper", "Message", "MessageQueue",
            "AsyncTask", "ProgressDialog", "AlertDialog", "Dialog", "Window", "WindowManager",
            "DisplayMetrics", "Gravity", "Animation", "AnimationUtils", "Interpolator",
            "Bitmap", "BitmapFactory", "Drawable", "Color", "Paint", "Canvas", "Rect",
            "OnClickListener", "OnLongClickListener", "OnTouchListener", "OnScrollListener",
            "OnKeyListener", "OnFocusChangeListener", "OnCheckedChangeListener",
            "OnClickListener", "OnTouchListener", "OnScrollListener", "OnKeyListener",
            "LayoutParams", "MarginLayoutParams", "RecyclerViewLayoutParams",
            "OnGlobalLayoutListener", "ViewTreeObserver", "WindowInsets",
            "InterruptedException", "RuntimeException", "Exception", "Throwable",
            "Comparable", "CharSequence", "StringBuffer", "Cloneable", "Serializable"
        ));
        return standardClasses.contains(simpleName);
    }

    /**
     * 判断是否是需要复制的项目内部类
     */
    private static boolean isProjectClass(String fullClassName) {
        if (fullClassName == null || fullClassName.isEmpty()) return false;
        
        // 排除 Java 标准库
        if (fullClassName.startsWith("java.") || fullClassName.startsWith("javax.") ||
            fullClassName.startsWith("sun.") || fullClassName.startsWith("com.sun.") ||
            fullClassName.startsWith("jdk.") || fullClassName.startsWith("org.w3c.") ||
            fullClassName.startsWith("org.xml.") || fullClassName.startsWith("org.omg.")) {
            return false;
        }
        
        // 排除常见第三方库
        if (fullClassName.startsWith("org.apache.") || fullClassName.startsWith("com.google.") ||
            fullClassName.startsWith("org.springframework.") || fullClassName.startsWith("lombok.") ||
            fullClassName.startsWith("org.slf4j.") || fullClassName.startsWith("org.junit.") ||
            fullClassName.startsWith("com.fasterxml.") || fullClassName.startsWith("android.")) {
            return false;
        }
        
        return true;
    }

    // ==================== 主方法（示例用法） ====================

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("用法: java DependencyCopier <项目根目录> <目标文件相对路径> <输出目录> [方法名]");
            System.err.println("示例: java DependencyCopier /home/user/myproject src/main/java/com/example/Main.java /tmp/output copyWithDependencies");
            System.exit(1);
        }
        
        try {
            String projectRoot = args[0];
            String targetFile = args[1];
            String outputDir = args[2];
            String targetMethod = args.length > 3 ? args[3] : null;
            
            List<File> copied = analyzeAndCopy(projectRoot, targetFile, targetMethod, outputDir);
            
            System.out.println("\n成功复制以下文件：");
            copied.forEach(f -> System.out.println("  " + f.getAbsolutePath()));
            
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
