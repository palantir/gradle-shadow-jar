/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.gradle.shadowjar;

import com.github.jengelman.gradle.plugins.shadow.transformers.Transformer;
import com.github.jengelman.gradle.plugins.shadow.transformers.TransformerContext;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.apache.tools.zip.ZipEntry;
import org.apache.tools.zip.ZipOutputStream;
import org.codehaus.plexus.util.IOUtil;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.tasks.Input;

// Originally taken from https://github.com/johnrengelman/shadow/blob/6.1.0/src/main/groovy/com/github/jengelman/
// gradle/plugins/shadow/transformers/ManifestAppenderTransformer.groovy
public final class ComposableManifestAppenderTransformer implements Transformer {
    private static final byte[] EOL = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SEPARATOR = ": ".getBytes(StandardCharsets.UTF_8);

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
    public void transform(TransformerContext context) {
        if (manifestContents.length == 0) {
            try {
                manifestContents = IOUtil.toByteArray(context.getIs());
                context.getIs().close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public String getName() {
        return ComposableManifestAppenderTransformer.class.getName();
    }

    @Override
    public boolean hasTransformedResource() {
        return !attributes.isEmpty();
    }

    @Override
    public void modifyOutputStream(ZipOutputStream os, boolean preserveFileTimestamps) {
        try {
            ZipEntry entry = new ZipEntry(JarFile.MANIFEST_NAME);
            entry.setTime(TransformerContext.getEntryTimestamp(preserveFileTimestamps, entry.getTime()));
            os.putNextEntry(entry);
            // Change: Trim existing file contents and add a single trailing newline
            os.write(trimWhitespace(manifestContents));
            os.write(EOL);

            if (!attributes.isEmpty()) {
                for (Attribute attribute : attributes) {
                    os.write(attribute.name().getBytes(StandardCharsets.UTF_8));
                    os.write(SEPARATOR);
                    os.write(attribute.value().toString().getBytes(StandardCharsets.UTF_8));
                    os.write(EOL);
                }
                os.write(EOL);
                attributes.clear();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Change: New method
    static byte[] trimWhitespace(byte[] contents) {
        return new String(contents, StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8);
    }

    public record Attribute(String name, Comparable<?> value) implements Serializable {}
}
