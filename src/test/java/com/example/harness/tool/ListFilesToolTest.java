package com.example.harness.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListFilesToolTest {
    @TempDir
    Path root;

    /** 验证排序、文件类型标注和非递归行为。 */
    @Test
    void listsDirectChildrenInNameOrder() throws Exception {
        Files.createFile(root.resolve("b.txt"));
        Files.createDirectories(root.resolve("a-dir"));
        Files.createFile(root.resolve("a-dir/hidden.txt"));
        assertEquals("[目录] a-dir\n[文件] b.txt", new ListFilesTool(root.toString()).listFiles("."));
    }

    /** 验证空目录提示及对越界、绝对路径和普通文件路径的拒绝。 */
    @Test
    void handlesEmptyDirectoryAndRejectsInvalidTargets() throws Exception {
        ListFilesTool tool = new ListFilesTool(root.toString());
        assertEquals("目录为空", tool.listFiles("."));
        assertThrows(IllegalArgumentException.class, () -> tool.listFiles(".."));
        assertThrows(IllegalArgumentException.class, () -> tool.listFiles(root.toAbsolutePath().toString()));
        Files.createFile(root.resolve("file.txt"));
        assertThrows(IllegalArgumentException.class, () -> tool.listFiles("file.txt"));
    }
}
