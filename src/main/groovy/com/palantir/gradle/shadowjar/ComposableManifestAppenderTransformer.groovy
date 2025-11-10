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

// Change: Different package, adapted from Shadow 9.2.2
package com.palantir.gradle.shadowjar

import org.codehaus.plexus.util.IOUtil

import static java.nio.charset.StandardCharsets.UTF_8
import static java.util.jar.JarFile.MANIFEST_NAME

import com.github.jengelman.gradle.plugins.shadow.transformers.ResourceTransformer
import com.github.jengelman.gradle.plugins.shadow.transformers.TransformerContext
import org.gradle.api.file.FileTreeElement
import org.gradle.api.tasks.Input
import org.apache.tools.zip.ZipEntry
import org.apache.tools.zip.ZipOutputStream

// Originally taken from https://github.com/johnrengelman/shadow/blob/6.1.0/src/main/groovy/com/github/jengelman/
// gradle/plugins/shadow/transformers/ManifestAppenderTransformer.groovy
class ComposableManifestAppenderTransformer implements ResourceTransformer {
    private static final byte[] EOL = "\r\n".getBytes(UTF_8)
    private static final byte[] SEPARATOR = ": ".getBytes(UTF_8)

    private byte[] manifestContents = []
    private final List<Tuple2<String, ? extends Comparable<?>>> attributes = []

    @Input
    List<Tuple2<String, ? extends Comparable<?>>> getAttributes() { attributes }

    ComposableManifestAppenderTransformer append(String name, Comparable<?> value) {
        attributes.add(new Tuple2<String, ? extends Comparable<?>>(name, value))
        this
    }

    @Override
    boolean canTransformResource(FileTreeElement element) {
        MANIFEST_NAME.equalsIgnoreCase(element.relativePath.pathString)
    }

    @Override
    void transform(TransformerContext context) {
        if (manifestContents.length == 0) {
            manifestContents = IOUtil.toByteArray(context.getInputStream())
            IOUtil.close(context.getInputStream())
        }
    }

    @Override
    boolean hasTransformedResource() {
        !attributes.isEmpty()
    }

    @Override
    void modifyOutputStream(ZipOutputStream os, boolean preserveFileTimestamps) {
        ZipEntry entry = new ZipEntry(MANIFEST_NAME)

        os.putNextEntry(entry)
        // Change: Trim existing file contents and add a single trailing newline
        os.write(trimWhitespace(manifestContents))
        os.write(EOL)

        if (!attributes.isEmpty()) {
            for (attribute in attributes) {
                os.write(attribute.v1.getBytes(UTF_8))
                os.write(SEPARATOR)
                os.write(attribute.v2.toString().getBytes(UTF_8))
                os.write(EOL)
            }
            os.write(EOL)
            attributes.clear()
        }
    }

    // Change: New method
    static byte[] trimWhitespace(byte[] contents) {
        return new String(contents, UTF_8).trim().getBytes(UTF_8);
    }
}
