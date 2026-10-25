package com.mahanapps.omnideck.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;

public class PathJailTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void testSafeChild() throws IOException {
        File root = temp.newFolder("jail");
        File resolved = PathJail.resolveSafe(root, "sub/file.txt");
        assertNotNull(resolved);
        assertTrue(resolved.getPath().startsWith(root.getCanonicalPath()));
    }

    @Test
    public void testRejectTraversal() throws IOException {
        File root = temp.newFolder("jail");
        assertNull(PathJail.resolveSafe(root, "../outside.txt"));
        assertNull(PathJail.resolveSafe(root, "../../etc/passwd"));
        assertNull(PathJail.resolveSafe(root, "sub/../../escape.txt"));
    }

    @Test
    public void testRejectBackslashAndNullByte() throws IOException {
        File root = temp.newFolder("jail");
        assertNull(PathJail.resolveSafe(root, "..\\escape.txt"));
        assertNull(PathJail.resolveSafe(root, "file.txt\0.exe"));
    }
}
