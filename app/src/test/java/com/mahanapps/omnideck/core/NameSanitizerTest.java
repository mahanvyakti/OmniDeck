package com.mahanapps.omnideck.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;

public class NameSanitizerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void testSanitizeBasic() {
        assertEquals("movie.mp4", NameSanitizer.sanitize("movie.mp4"));
        assertEquals("report.pdf", NameSanitizer.sanitize("report.pdf"));
    }

    @Test
    public void testSanitizePathTraversal() {
        assertEquals("etc_passwd", NameSanitizer.sanitize("../../etc/passwd"));
        assertEquals("windows_system32_cmd.exe", NameSanitizer.sanitize("..\\..\\windows\\system32\\cmd.exe"));
    }

    @Test
    public void testSanitizeUnicodeHindi() {
        String hindi = "नमस्ते_दुनिया.txt";
        assertEquals("नमस्ते_दुनिया.txt", NameSanitizer.sanitize(hindi));
    }

    @Test
    public void testSanitizeNullAndEmpty() {
        assertEquals("unnamed_file", NameSanitizer.sanitize(null));
        assertEquals("unnamed_file", NameSanitizer.sanitize(""));
        assertEquals("unnamed_file", NameSanitizer.sanitize("   "));
        assertEquals("unnamed_file", NameSanitizer.sanitize("..."));
    }

    @Test
    public void testResolveUniqueFile() throws IOException {
        File dir = temp.newFolder();
        File f1 = new File(dir, "test.txt");
        assertTrue(f1.createNewFile());

        File resolved = NameSanitizer.resolveUniqueFile(dir, "test.txt");
        assertEquals("test (1).txt", resolved.getName());

        assertTrue(resolved.createNewFile());
        File resolved2 = NameSanitizer.resolveUniqueFile(dir, "test.txt");
        assertEquals("test (2).txt", resolved2.getName());
    }
}
