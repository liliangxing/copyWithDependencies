package com.example.util;

/**
 * DependencyCopier 使用示例
 * 
 * 这个类演示如何使用 DependencyCopier 工具
 */
public class DependencyCopierExample {

    public static void main(String[] args) {
        // 示例1：复制单个文件及其所有依赖
        example1_CopySingleFile();

        // 示例2：编程方式调用
        example2_ProgrammaticUsage();
    }

    /**
     * 示例1：通过命令行运行
     * 
     * 编译：
     * javac -d out src/main/java/com/example/util/DependencyCopier.java
     * 
     * 运行：
     * java -cp out com.example.util.DependencyCopier \
     *   /home/user/myproject/src/main/java/com/example/Main.java \
     *   /tmp/output \
     *   /home/user/myproject/src/main/java
     */
    private static void example1_CopySingleFile() {
        System.out.println("命令行使用示例:");
        System.out.println("java com.example.util.DependencyCopier \\");
        System.out.println("  src/main/java/com/example/Main.java \\");
        System.out.println("  /tmp/output \\");
        System.out.println("  src/main/java");
        System.out.println();
    }

    /**
     * 示例2：在代码中编程调用
     */
    private static void example2_ProgrammaticUsage() {
        try {
            String sourceFile = "/home/user/myproject/src/main/java/com/example/Main.java";
            String outputDir = "/tmp/output";
            String sourceRoot = "/home/user/myproject/src/main/java";

            DependencyCopier.copyWithDependencies(sourceFile, outputDir, sourceRoot);

            System.out.println("复制完成！");
        } catch (Exception e) {
            System.err.println("复制失败: " + e.getMessage());
        }
    }
}
