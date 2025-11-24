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

package com.palantir.gradle.shadowjar;

import com.github.jengelman.gradle.plugins.shadow.relocation.CacheableRelocator;
import com.github.jengelman.gradle.plugins.shadow.relocation.SimpleRelocator;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.Classpath;

/**
 * Custom relocator that uses Set-based lookups instead of SimpleRelocator's include/exclude pattern matching.
 *
 * <p>We don't use SimpleRelocator's include list because it uses SelectorUtils.matchPath() for every pattern
 * against every file in the jar, which is O(n*m) where n = number of files and m = number of patterns.
 * This causes a massive performance penalty (e.g., 8 minutes vs 13 seconds for tests).
 *
 * <p>Instead, we compute a Set of relocatable paths upfront and use O(1) Set lookups in canRelocatePath().
 */
@CacheableRelocator
class JarFilesRelocator extends SimpleRelocator {
    private static final Logger log = Logging.getLogger(JarFilesRelocator.class);
    private static final Pattern MULTIRELEASE_JAR_PREFIX = Pattern.compile("^META-INF/versions/\\d+/");
    private static final String SERVICE_PROVIDER_PREFIX = "META-INF/services/";
    private static final String CLASS_SUFFIX = ".class";

    private final FileCollection jars;
    private final Supplier<Set<String>> relocatable;

    JarFilesRelocator(String prefix, FileCollection jars) {
        super("", prefix, ImmutableList.of(), ImmutableList.of());
        this.jars = jars;
        this.relocatable = Suppliers.memoize(() -> computeRelocatablePaths(scanJarsForPaths(jars)));
    }

    @Classpath
    public final FileCollection getJars() {
        return jars;
    }

    @Override
    public boolean canRelocatePath(String path) {
        return relocatable.get().contains(path + CLASS_SUFFIX)
                || relocatable.get().contains(path);
    }

    private static Set<String> scanJarsForPaths(FileCollection jars) {
        return jars.getFiles().stream()
                .flatMap(jar -> {
                    try (JarFile jarFile = new JarFile(jar)) {
                        return Collections.list(jarFile.entries()).stream()
                                .filter(entry -> !entry.isDirectory())
                                .map(ZipEntry::getName)
                                .peek(path -> log.debug("Jar '{}' contains entry '{}'", jar.getName(), path))
                                .peek(path -> Preconditions.checkState(
                                        !path.startsWith("/"), "Unexpected absolute path '%s' in jar '%s'", path, jar));
                    } catch (IOException e) {
                        throw new UncheckedIOException("Could not open jar file", e);
                    }
                })
                .collect(Collectors.toSet());
    }

    private static Set<String> computeRelocatablePaths(Set<String> pathsInJars) {
        // For multi-release JARs (e.g., META-INF/versions/9/com/foo/Bar.class), we need to add the
        // unprefixed path (com/foo/Bar.class) to the relocatable set. This ensures that bytecode references
        // to com.foo.Bar are properly relocated, even though the actual file path relocation is handled by
        // ShadowCopyAction which temporarily removes the prefix before calling relocatePath.
        Set<String> multiReleaseStuff = pathsInJars.stream()
                .flatMap(JarFilesRelocator::extractMultiReleasePath)
                .collect(Collectors.toSet());

        return Stream.concat(pathsInJars.stream(), multiReleaseStuff.stream())
                .filter(path -> !path.equals("META-INF/MANIFEST.MF")) // don't relocate this!
                .filter(path -> !path.startsWith(SERVICE_PROVIDER_PREFIX)) // service providers remain in the root
                .collect(Collectors.toSet());
    }

    /*
      Returns the path without the multi-release prefix e.g.
      'com/foo/Bar.class' from 'META-INF/versions/9/com/foo/Bar.class'.
    */
    private static Stream<String> extractMultiReleasePath(String input) {
        Matcher matcher = MULTIRELEASE_JAR_PREFIX.matcher(input);
        if (matcher.find()) {
            return Stream.of(input.substring(matcher.end()));
        } else {
            return Stream.empty();
        }
    }
}
