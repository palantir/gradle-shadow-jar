/*
 * (c) Copyright 2025 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Change: Different package, adapted from Shadow 9.2.2
package com.palantir.gradle.shadowjar;

import com.github.jengelman.gradle.plugins.shadow.transformers.ResourceTransformer;
import com.github.jengelman.gradle.plugins.shadow.transformers.TransformerContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.jar.JarFile;
import org.apache.tools.zip.ZipEntry;
import org.apache.tools.zip.ZipOutputStream;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.tasks.Input;

// Originally taken from https://github.com/GradleUp/shadow/blob/9.2.2/src/main/kotlin/com/github/jengelman/gradle/
// plugins/shadow/transformers/ManifestAppenderTransformer.kt
public final class ComposableManifestAppenderTransformer implements ResourceTransformer {
    private static final byte[] EOL = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SEPARATOR = ": ".getBytes(StandardCharsets.UTF_8);

    public static final long CONSTANT_TIME_FOR_ZIP_ENTRIES =
            new GregorianCalendar(1980, Calendar.FEBRUARY, 1, 0, 0, 0).getTimeInMillis();

    private byte[] manifestContents = new byte[0];
    private final List<Attribute> attributes = new ArrayList<>();

    @Input
    public List<Attribute> getAttributes() {
        return attributes;
    }

    public ComposableManifestAppenderTransformer append(String name, Comparable<?> value) {
        attributes.add(new Attribute(name, value));
        return this;
    }

    @Override
    public boolean canTransformResource(FileTreeElement element) {
        return JarFile.MANIFEST_NAME.equalsIgnoreCase(element.getRelativePath().getPathString());
    }

    @Override
    public void transform(TransformerContext context) throws IOException {
        if (manifestContents.length == 0) {
            try (InputStream is = context.getInputStream()) {
                manifestContents = is.readAllBytes();
            }
        }
    }

    @Override
    public boolean hasTransformedResource() {
        return !attributes.isEmpty();
    }

    @Override
    public void modifyOutputStream(ZipOutputStream os, boolean preserveFileTimestamps) throws IOException {
        ZipEntry entry = zipEntry(JarFile.MANIFEST_NAME, preserveFileTimestamps);

        os.putNextEntry(entry);
        // Change: Trim existing file contents and add a single trailing newline
        os.write(trimWhitespace(manifestContents));
        os.write(EOL);

        if (!attributes.isEmpty()) {
            for (Attribute attribute : attributes) {
                os.write(attribute.name.getBytes(StandardCharsets.UTF_8));
                os.write(SEPARATOR);
                os.write(attribute.value.toString().getBytes(StandardCharsets.UTF_8));
                os.write(EOL);
            }
            os.write(EOL);
            attributes.clear();
        }

        os.closeEntry();
    }

    // Change: New method
    static byte[] trimWhitespace(byte[] contents) {
        return new String(contents, StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8);
    }

    public record Attribute(String name, Comparable<?> value) {}

    public static ZipEntry zipEntry(String name, boolean preserveLastModified) {
        ZipEntry entry = new ZipEntry(name);
        if (!preserveLastModified) {
            entry.setTime(CONSTANT_TIME_FOR_ZIP_ENTRIES);
        }
        return entry;
    }
}
