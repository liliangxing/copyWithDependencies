package com.example.util;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

/**
 * Java方法级级联依赖分析与复制工具类（V5）
 * 
 * 功能特点：
 * 1. 提取指定方法体（a方法）
 * 2. 复制a方法直接依赖的文件
 * 3. 识别a方法中调用的其他方法（b, c, d...）
 * 4. 对这些被调用的方法，继续分析并复制它们的直接依赖
 * 5. 如果文件已复制过则跳过，防止重复
 * 
 * 使用方法：
 * java DependencyCopierV5 <源路径> <目标路径> <文件路径> <方法名>
 */
public class DependencyCopierV5 {
    
    private final Path sourcePath;
    private final Path targetPath;
    private final Set<String> processedFiles = new HashSet<>();
    private final Set<String> processedMethods = new HashSet<>();
    private final Map<String, List<Path>> classNameToPaths = new HashMap<>();
    private final Map<String, Set<String>> classMethodsIndex = new HashMap<>();
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
    
    public DependencyCopierV5(String sourcePath, String targetPath) {
        this.sourcePath = Paths.get(sourcePath);
        this.targetPath = Paths.get(targetPath);
    }
    
    /**
     * 复制指定文件的级联方法依赖
     * @param filePath 源文件路径
     * @param methodName 起始方法名
     */
    public void copyCascadeDependencies(String filePath, String methodName) throws IOException {
        Path file = Paths.get(filePath).toAbsolutePath().normalize();
        if (!Files.exists(file)) {
            throw new FileNotFoundException("源文件不存在: " + file);
        }
        
        if (!Files.exists(targetPath)) {
            Files.createDirectories(targetPath);
        }
        
        // 构建类名映射和方法索引
        buildClassNameMapping(sourcePath);
        
        System.out.println("已索引 " + classMethodsIndex.size() + " 个类的方法");
        
        // 先复制起始文件
        processFile(file);
        
        // 递归分析方法依赖
        analyzeAndCopyMethod(file, methodName, 0);
        
        System.out.println("\n复制完成! 共复制 " + processedFiles.size() + " 个文件到 " + targetPath);
    }
    
    /**
     * 递归分析方法并复制依赖
     * @param file 方法所在文件
     * @param methodName 方法名
     * @param depth 递归深度（用于日志）
     */
    private void analyzeAndCopyMethod(Path file, String methodName, int depth) throws IOException {
        String fullClassName = extractClassName(file, sourcePath);
        String methodKey = fullClassName + "." + methodName;
        
        if (processedMethods.contains(methodKey)) {
            return;
        }
        processedMethods.add(methodKey);
        
        // 读取文件内容
        String content = new String(Files.readAllBytes(file));
        
        // 提取方法体
        String methodBody = extractMethodBody(content, methodName);
        if (methodBody == null) {
            return;
        }
        
        // 移除字符串和注释
        String cleanedMethodBody = removeStringsAndComments(methodBody);
        String cleanedContent = removeStringsAndComments(content);
        
        // 解析import和package
        String packageName = parsePackage(cleanedContent);
        Map<String, String> importMap = parseImports(cleanedContent);
        
        // 1. 收集方法直接依赖的类（类型引用）
        Set<String> methodDeps = collectMethodDependencies(cleanedMethodBody, packageName, importMap);
        
        // 2. 复制依赖的文件
        for (String dep : methodDeps) {
            List<Path> depFiles = findClassFiles(dep);
            for (Path depFile : depFiles) {
                if (!processedFiles.contains(depFile.toString())) {
                    String indent = getIndent(depth);
                    System.out.println(indent + "-> 复制依赖文件: " + sourcePath.relativize(depFile));
                    processFile(depFile);
                }
            }
        }
        
        // 3. 识别方法体中调用的其他项目内方法
        List<MethodCall> calledMethods = findCalledMethods(cleanedMethodBody, packageName, importMap, file, fullClassName);
        System.out.println("  " + methodName + " 调用了 " + calledMethods.size() + " 个项目内方法");
        
        // 4. 递归处理被调用的方法
        for (MethodCall call : calledMethods) {
            String targetMethodKey = call.className + "." + call.methodName;
            if (processedMethods.contains(targetMethodKey)) {
                continue;
            }
            
            // 查找目标类文件
            List<Path> targetFiles = findClassFiles(call.className);
            if (targetFiles.isEmpty() && packageName != null) {
                targetFiles = findClassFiles(packageName + "." + call.className);
            }
            
            for (Path targetFile : targetFiles) {
                if (!processedMethods.contains(extractClassName(targetFile, sourcePath) + "." + call.methodName)) {
                    String indent = getIndent(depth + 1);
                    System.out.println(indent + ">> 分析方法: " + call.className + "." + call.methodName);
                    analyzeAndCopyMethod(targetFile, call.methodName, depth + 1);
                }
            }
        }
    }
    
    /**
     * 方法调用信息
     */
    static class MethodCall {
        final String className;
        final String methodName;
        
        MethodCall(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MethodCall methodCall = (MethodCall) o;
            return className.equals(methodCall.className) && methodName.equals(methodCall.methodName);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(className, methodName);
        }
    }
    
    /**
     * 收集方法直接依赖的类
     */
    private Set<String> collectMethodDependencies(String cleanedMethodBody, String packageName, Map<String, String> importMap) {
        Set<String> dependencies = new HashSet<>();
        
        // 全限定类名
        Pattern fqPattern = Pattern.compile("\\b([a-z][a-zA-Z0-9_]*(?:\\.[a-z][a-zA-Z0-9_]*)+\\.[A-Z][a-zA-Z0-9_]*)\\b");
        Matcher fqMatcher = fqPattern.matcher(cleanedMethodBody);
        while (fqMatcher.find()) {
            dependencies.add(fqMatcher.group(1));
        }
        
        // 简单类名通过import映射
        Pattern simplePattern = Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\b");
        Matcher simpleMatcher = simplePattern.matcher(cleanedMethodBody);
        while (simpleMatcher.find()) {
            String simpleName = simpleMatcher.group(1);
            if (importMap.containsKey(simpleName)) {
                dependencies.add(importMap.get(simpleName));
            }
        }
        
        // 同包类
        Set<String> samePkgClasses = findSamePackageClasses(cleanedMethodBody, importMap.keySet());
        for (String cls : samePkgClasses) {
            if (packageName != null && !packageName.isEmpty()) {
                dependencies.add(packageName + "." + cls);
            }
        }
        
        // 过滤标准库
        dependencies.removeIf(this::isStandardLibrary);
        
        return dependencies;
    }
    
    /**
     * 识别方法体中调用的其他项目内方法
     * 关键改进：只识别那些方法定义在项目源文件中的调用
     */
    private List<MethodCall> findCalledMethods(String cleanedMethodBody, String packageName, 
                                                 Map<String, String> importMap, Path currentFile, 
                                                 String fullClassName) {
        Set<MethodCall> calledMethods = new LinkedHashSet<>();
        String currentSimpleClassName = fullClassName.substring(fullClassName.lastIndexOf('.') + 1);
        Set<String> currentFileMethods = classMethodsIndex.getOrDefault(fullClassName, Collections.emptySet());
        
        // 1. 匹配 ClassName.staticMethod() 形式 - 静态方法调用
        Pattern staticMethodPattern = Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\s*\\.\\s*([a-z][a-zA-Z0-9_]*)\\s*\\(");
        Matcher staticMatcher = staticMethodPattern.matcher(cleanedMethodBody);
        while (staticMatcher.find()) {
            String className = staticMatcher.group(1);
            String methodName = staticMatcher.group(2);
            
            // 跳过标准库
            if (standardClassNames.contains(className)) continue;
            
            // 解析为全限定类名
            String fullTargetClass = resolveClassName(className, importMap, packageName);
            if (fullTargetClass == null || isStandardLibrary(fullTargetClass)) continue;
            
            // 检查该方法是否在项目索引中
            Set<String> targetMethods = classMethodsIndex.get(fullTargetClass);
            if (targetMethods != null && targetMethods.contains(methodName)) {
                calledMethods.add(new MethodCall(fullTargetClass, methodName));
            }
        }
        
        // 2. 匹配 obj.method() 形式 - 实例方法调用
        // 需要先识别变量类型，这需要分析方法体内的变量声明
        Map<String, String> variableTypes = extractVariableTypes(cleanedMethodBody, importMap, packageName);
        
        Pattern objMethodPattern = Pattern.compile("\\b([a-z][a-zA-Z0-9_]*)\\s*\\.\\s*([a-z][a-zA-Z0-9_]*)\\s*\\(");
        Matcher objMatcher = objMethodPattern.matcher(cleanedMethodBody);
        while (objMatcher.find()) {
            String objName = objMatcher.group(1);
            String methodName = objMatcher.group(2);
            
            // 跳过关键字和标准对象
            if (objName.equals("this") || objName.equals("super")) continue;
            
            // 获取对象类型
            String objType = variableTypes.get(objName);
            if (objType == null) {
                // 尝试从import中找（可能是字段或参数）
                objType = importMap.get(objName);
            }
            if (objType == null || isStandardLibrary(objType)) continue;
            
            // 检查该方法是否在目标类的索引中
            Set<String> targetMethods = classMethodsIndex.get(objType);
            if (targetMethods != null && targetMethods.contains(methodName)) {
                calledMethods.add(new MethodCall(objType, methodName));
            }
        }
        
        // 3. 匹配无对象前缀的方法调用 method() - 同文件中的方法
        // 匹配独立的 methodName( 形式，前面没有 . 
        Pattern bareMethodPattern = Pattern.compile("(?<![.\\w])([a-z][a-zA-Z0-9_]*)\\s*\\(");
        Matcher bareMatcher = bareMethodPattern.matcher(cleanedMethodBody);
        while (bareMatcher.find()) {
            String methodName = bareMatcher.group(1);
            
            // 跳过Java关键字和控制流
            if (isJavaKeyword(methodName)) continue;
            
            // 检查当前文件是否有这个方法
            if (currentFileMethods.contains(methodName)) {
                calledMethods.add(new MethodCall(fullClassName, methodName));
            }
        }
        
        return new ArrayList<>(calledMethods);
    }
    
    /**
     * 从方法体中提取变量声明的类型
     */
    private Map<String, String> extractVariableTypes(String cleanedMethodBody, Map<String, String> importMap, String packageName) {
        Map<String, String> variableTypes = new HashMap<>();
        
        // 匹配 类型 变量名 = ...
        Pattern varPattern = Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*(?:<[^>]*>)?(?:\\[\\])?)\\s+([a-z][a-zA-Z0-9_]*)\\s*[=;]");
        Matcher varMatcher = varPattern.matcher(cleanedMethodBody);
        while (varMatcher.find()) {
            String type = varMatcher.group(1);
            String varName = varMatcher.group(2);
            String fullType = resolveClassName(type, importMap, packageName);
            if (fullType != null) {
                variableTypes.put(varName, fullType);
            }
        }
        
        // 匹配 new Type(...) 形式的变量
        Pattern newVarPattern = Pattern.compile("\\b([a-z][a-zA-Z0-9_]*)\\s*=\\s*new\\s+([A-Z][a-zA-Z0-9_]*)");
        Matcher newVarMatcher = newVarPattern.matcher(cleanedMethodBody);
        while (newVarMatcher.find()) {
            String varName = newVarMatcher.group(1);
            String type = newVarMatcher.group(2);
            String fullType = resolveClassName(type, importMap, packageName);
            if (fullType != null) {
                variableTypes.put(varName, fullType);
            }
        }
        
        return variableTypes;
    }
    
    /**
     * 解析简单类名为全限定类名
     */
    private String resolveClassName(String simpleName, Map<String, String> importMap, String packageName) {
        if (simpleName.contains(".")) {
            // 已经是全限定名
            return isStandardLibrary(simpleName) ? null : simpleName;
        }
        
        if (importMap.containsKey(simpleName)) {
            return importMap.get(simpleName);
        }
        
        if (standardClassNames.contains(simpleName)) {
            return "java.lang." + simpleName;
        }
        
        // 尝试在同包下查找
        if (packageName != null) {
            String candidate = packageName + "." + simpleName;
            if (classNameToPaths.containsKey(simpleName) || classNameToPaths.containsKey(candidate)) {
                return candidate;
            }
        }
        
        // 检查是否是项目中的类
        if (classNameToPaths.containsKey(simpleName)) {
            return simpleName;
        }
        
        return null;
    }
    
    /**
     * 判断是否是Java关键字或控制流
     */
    private boolean isJavaKeyword(String word) {
        return word.equals("if") || word.equals("else") || word.equals("for") || word.equals("while") ||
               word.equals("do") || word.equals("switch") || word.equals("case") || word.equals("return") ||
               word.equals("try") || word.equals("catch") || word.equals("finally") || word.equals("throw") ||
               word.equals("new") || word.equals("class") || word.equals("interface") || word.equals("enum") ||
               word.equals("super") || word.equals("this") || word.equals("true") || word.equals("false") ||
               word.equals("null") || word.equals("assert") || word.equals("break") || word.equals("continue") ||
               word.equals("default") || word.equals("instanceof") || word.equals("synchronized");
    }
    
    /**
     * 提取方法体
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
    
    /**
     * 构建类名映射和方法索引
     */
    private void buildClassNameMapping(Path rootPath) throws IOException {
        Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    try {
                        String content = new String(Files.readAllBytes(file));
                        String className = extractClassName(file, rootPath);
                        classNameToPaths.computeIfAbsent(className, k -> new ArrayList<>()).add(file);
                        
                        String simpleName = getSimpleClassName(className);
                        if (!simpleName.equals(className)) {
                            classNameToPaths.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(file);
                        }
                        
                        // 提取该类的所有方法名
                        Set<String> methods = extractMethodNames(content);
                        classMethodsIndex.put(className, methods);
                        
                        String simpleClassName = className.substring(className.lastIndexOf('.') + 1);
                        if (!classMethodsIndex.containsKey(simpleClassName)) {
                            classMethodsIndex.put(simpleClassName, methods);
                        }
                    } catch (IOException e) {
                        // 忽略读取错误
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
     * 从Java文件中提取所有方法名
     */
    private Set<String> extractMethodNames(String content) {
        Set<String> methods = new HashSet<>();
        String cleaned = removeStringsAndComments(content);
        
        // 匹配方法声明
        Pattern methodPattern = Pattern.compile(
            "(?:(?:public|private|protected|static|final|native|synchronized|abstract|transient)\\s+)*" +
            "[\\w<>\\[\\],\\.\\s]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w\\.,\\s]+)?\\s*\\{"
        );
        Matcher matcher = methodPattern.matcher(cleaned);
        while (matcher.find()) {
            String methodName = matcher.group(1);
            // 过滤关键字
            if (!isJavaKeyword(methodName)) {
                methods.add(methodName);
            }
        }
        
        return methods;
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
        
        System.out.println("  已复制: " + sourcePath.relativize(file));
        
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
                        System.out.println("  已复制内部类: " + sourcePath.relativize(nested));
                    }
                }
            }
        }
    }
    
    /**
     * Java 8兼容的缩进生成
     */
    private String getIndent(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }
    
    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("用法: java DependencyCopierV5 <源路径> <目标路径> <文件路径> <方法名>");
            System.out.println("示例: java DependencyCopierV5 /path/to/src /tmp/output /path/to/Main.java myMethod");
            return;
        }
        
        String sourcePath = args[0];
        String targetPath = args[1];
        String filePath = args[2];
        String methodName = args[3];
        
        try {
            DependencyCopierV5 copier = new DependencyCopierV5(sourcePath, targetPath);
            copier.copyCascadeDependencies(filePath, methodName);
        } catch (IOException e) {
            System.err.println("处理过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
