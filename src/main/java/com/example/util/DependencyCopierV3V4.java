package com.example.util;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Java方法级依赖分析与复制工具类（V3增强版 - V4）
 * 基于V3的类名映射机制，仅分析方法体内使用的依赖
 * 
 * 功能特点：
 * 1. 提取指定方法体
 * 2. 仅分析方法体内的依赖
 * 3. 复制这些依赖所在的文件（不递归传递依赖）
 * 
 * 使用方法：
 * java DependencyCopierV3V4 <源路径> <目标路径> <文件路径> <方法名>
 */
public class DependencyCopierV3V4 {
    
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
    private final Set<String> standardClassNames = new HashSet<>(Arrays.asList(
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
    
    public DependencyCopierV3V4(String sourcePath, String targetPath) {
        this.sourcePath = Paths.get(sourcePath);
        this.targetPath = Paths.get(targetPath);
    }
    
    /**
     * 复制指定文件的方法级依赖
     * @param filePath 源文件路径
     * @param methodName 方法名
     */
    public void copyMethodDependencies(String filePath, String methodName) throws IOException {
        Path file = Paths.get(filePath).toAbsolutePath().normalize();
        if (!Files.exists(file)) {
            throw new FileNotFoundException("源文件不存在: " + file);
        }
        
        if (!Files.exists(targetPath)) {
            Files.createDirectories(targetPath);
        }
        
        // 构建类名映射
        buildClassNameMapping(sourcePath);
        
        // 读取文件内容
        String content = new String(Files.readAllBytes(file));
        
        // 提取方法体
        String methodBody = extractMethodBody(content, methodName);
        if (methodBody == null) {
            throw new IllegalArgumentException("未找到方法: " + methodName);
        }
        
        // 移除字符串和注释
        String cleanedMethodBody = removeStringsAndComments(methodBody);
        String cleanedContent = removeStringsAndComments(content);
        
        // 解析import和package
        String packageName = parsePackage(cleanedContent);
        Map<String, String> importMap = parseImports(cleanedContent);
        
        // 仅从方法体中收集依赖
        Set<String> methodDeps = new HashSet<>();
        
        // 全限定类名
        Pattern fqPattern = Pattern.compile("\\b([a-z][a-zA-Z0-9_]*(?:\\.[a-z][a-zA-Z0-9_]*)+\\.[A-Z][a-zA-Z0-9_]*)\\b");
        Matcher fqMatcher = fqPattern.matcher(cleanedMethodBody);
        while (fqMatcher.find()) {
            methodDeps.add(fqMatcher.group(1));
        }
        
        // 简单类名通过import映射
        Pattern simplePattern = Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\b");
        Matcher simpleMatcher = simplePattern.matcher(cleanedMethodBody);
        while (simpleMatcher.find()) {
            String simpleName = simpleMatcher.group(1);
            if (importMap.containsKey(simpleName)) {
                methodDeps.add(importMap.get(simpleName));
            }
        }
        
        // 同包类
        Set<String> samePkgClasses = findSamePackageClasses(cleanedMethodBody, importMap.keySet());
        for (String cls : samePkgClasses) {
            if (packageName != null && !packageName.isEmpty()) {
                methodDeps.add(packageName + "." + cls);
            }
        }
        
        // 过滤项目类
        methodDeps.removeIf(this::isStandardLibrary);
        
        System.out.println("方法 '" + methodName + "' 使用了 " + methodDeps.size() + " 个项目内部类:");
        methodDeps.forEach(dep -> System.out.println("  - " + dep));
        
        // 复制目标文件
        processFile(file);
        
        // 复制依赖文件（不递归）
        for (String dep : methodDeps) {
            List<Path> depFiles = findClassFiles(dep);
            for (Path depFile : depFiles) {
                if (!processedFiles.contains(depFile.toString())) {
                    processFile(depFile);
                }
            }
        }
        
        System.out.println("复制完成! 共复制 " + processedFiles.size() + " 个文件到 " + targetPath);
    }
    
    /**
     * 提取方法体（包括构造函数）
     */
    private String extractMethodBody(String content, String methodName) {
        String actualMethodName = methodName;
        
        if ("<init>".equals(methodName)) {
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
    
    private String parsePackage(String content) {
        Pattern pattern = Pattern.compile("^\\s*package\\s+([^;]+);", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
    
    private Map<String, String> parseImports(String content) {
        Map<String, String> imports = new HashMap<>();
        Pattern pattern = Pattern.compile("^\\s*import\\s+(static\\s+)?([^;]+);", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            String fullName = matcher.group(2).trim();
            if (fullName.endsWith(".*")) continue;
            
            int lastDot = fullName.lastIndexOf('.');
            String simpleName = lastDot > 0 ? fullName.substring(lastDot + 1) : fullName;
            imports.put(simpleName, fullName);
        }
        
        return imports;
    }
    
    private Set<String> findSamePackageClasses(String cleanedContent, Set<String> importedNames) {
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
                String cls = m.group(1);
                if (!importedNames.contains(cls) && !standardClassNames.contains(cls)) {
                    classes.add(cls);
                }
            }
        }
        
        return classes;
    }
    
    private boolean isStandardLibrary(String className) {
        for (String prefix : standardLibPrefixes) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }
    
    private void buildClassNameMapping(Path rootPath) throws IOException {
        Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    String className = extractClassName(file, rootPath);
                    classNameToPaths.computeIfAbsent(className, k -> new ArrayList<>()).add(file);
                    
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
    
    private String extractClassName(Path file, Path rootPath) {
        String relativePath = rootPath.relativize(file).toString();
        return relativePath.replace(File.separatorChar, '.').replace(".java", "");
    }
    
    private String getSimpleClassName(String fullClassName) {
        int lastDot = fullClassName.lastIndexOf('.');
        return lastDot >= 0 ? fullClassName.substring(lastDot + 1) : fullClassName;
    }
    
    private List<Path> findClassFiles(String className) {
        List<Path> result = new ArrayList<>();
        if (classNameToPaths.containsKey(className)) {
            result.addAll(classNameToPaths.get(className));
        }
        
        if (result.isEmpty() && className.contains(".")) {
            String pathName = className.replace('.', File.separatorChar) + ".java";
            Path potentialPath = sourcePath.resolve(pathName);
            if (Files.exists(potentialPath)) {
                result.add(potentialPath);
            }
        }
        
        return result;
    }
    
    private void processFile(Path file) throws IOException {
        if (processedFiles.contains(file.toString())) {
            return;
        }
        
        String className = extractClassName(file, sourcePath);
        Path targetFile = targetPath.resolve(className.replace('.', File.separatorChar) + ".java");
        
        Files.createDirectories(targetFile.getParent());
        Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
        processedFiles.add(file.toString());
        
        System.out.println("已复制: " + sourcePath.relativize(file));
        
        copyNestedClasses(file);
    }
    
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
    
    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("用法: java DependencyCopierV3V4 <源路径> <目标路径> <文件路径> <方法名>");
            System.out.println("示例: java DependencyCopierV3V4 /path/to/src /tmp/output /path/to/Main.java myMethod");
            return;
        }
        
        String sourcePath = args[0];
        String targetPath = args[1];
        String filePath = args[2];
        String methodName = args[3];
        
        try {
            DependencyCopierV3V4 copier = new DependencyCopierV3V4(sourcePath, targetPath);
            copier.copyMethodDependencies(filePath, methodName);
        } catch (IOException e) {
            System.err.println("处理过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
