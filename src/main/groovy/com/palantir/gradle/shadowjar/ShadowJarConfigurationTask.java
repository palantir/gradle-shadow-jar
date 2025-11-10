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

import com.github.jengelman.gradle.plugins.shadow.relocation.CacheableRelocator;
import com.github.jengelman.gradle.plugins.shadow.relocation.RelocateClassContext;
import com.github.jengelman.gradle.plugins.shadow.relocation.RelocatePathContext;
import com.github.jengelman.gradle.plugins.shadow.relocation.SimpleRelocator;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Originally taken from https://github.com/GradleUp/shadow/blob/9.2.2/src/main/groovy/com/github/jengelman/
// gradle/plugins/shadow/tasks/ConfigureShadowRelocation.groovy
// Note: ConfigureShadowRelocation was removed in Shadow 8.1.0 in favor of enableAutoRelocation property
public abstract class ShadowJarConfigurationTask {

    private static final Logger log = LoggerFactory.getLogger(ShadowJarConfigurationTask.class);

    private static final String CLASS_SUFFIX = ".class";

    // Multi-Release JAR Files are defined in https://openjdk.java.net/jeps/238
    private static final Pattern MULTIRELEASE_JAR_PREFIX = Pattern.compile("^META-INF/versions/\\d+/");
    private static final String SERVICE_PROVIDER_PREFIX = "META-INF/services/";

    /** Scan jars and return all paths found within them */
    public static Set<String> scanJarsForPaths(org.gradle.api.file.FileCollection jars) {
        return jars.getFiles().stream()
                .flatMap(jar -> {
                    try (JarFile jarFile = new JarFile(jar)) {
                        return Collections.list(jarFile.entries()).stream()
                                .filter(entry -> !entry.isDirectory())
                                .map(ZipEntry::getName)
                                .peek(path -> log.debug("Jar '{}' contains entry '{}'", jar.getName(), path))
                                .peek(path -> Preconditions.checkState(
                                        !path.startsWith("/"), "Unexpected absolute path '%s' in jar '%s'", path, jar))
                                .toList()
                                .stream();
                    } catch (IOException e) {
                        throw new RuntimeException("Could not open jar file", e);
                    }
                })
                .collect(Collectors.toSet());
    }

    /** Compute the set of paths that should be relocated */
    public static Set<String> computeRelocatablePaths(Set<String> pathsInJars) {
        // The Relocator is responsible for fixing the bytecode at callsites *and* filenames of .class files,
        // so we have to account for things _calling_ these weird multi-release classes.
        Set<String> multiReleaseStuff = pathsInJars.stream()
                .flatMap(input -> splitMultiReleasePath(input).stream().skip(1))
                .collect(Collectors.toSet());

        return Stream.concat(pathsInJars.stream(), multiReleaseStuff.stream())
                .filter(path -> !path.equals("META-INF/MANIFEST.MF")) // don't relocate this!
                .filter(path -> !path.startsWith(SERVICE_PROVIDER_PREFIX)) // service providers remain in the root
                .collect(Collectors.toSet());
    }

    /** Check if any of the paths indicate a multi-release JAR */
    public static boolean hasMultiRelease(Set<String> pathsInJars) {
        return pathsInJars.stream()
                .anyMatch(path -> MULTIRELEASE_JAR_PREFIX.matcher(path).find());
    }

    /** Returns a pair of 'META-INF/versions/9/' and 'com/foo/whatever.class'. */
    static List<String> splitMultiReleasePath(String input) {
        Matcher matcher = MULTIRELEASE_JAR_PREFIX.matcher(input);
        if (matcher.find()) {
            return ImmutableList.of(input.substring(0, matcher.end()), input.substring(matcher.end()));
        } else {
            return ImmutableList.of();
        }
    }

    @CacheableRelocator
    public static final class JarFilesRelocator extends SimpleRelocator {
        private final Set<String> relocatable;

        public JarFilesRelocator(Set<String> relocatable, String shadedPrefix) {
            super("", shadedPrefix, ImmutableList.of(), ImmutableList.of());
            this.relocatable = relocatable;
        }

        @Override
        public boolean canRelocatePath(String path) {
            return relocatable.contains(path + CLASS_SUFFIX) || relocatable.contains(path);
        }

        @Override
        public String relocatePath(RelocatePathContext context) {
            List<String> maybePair = splitMultiReleasePath(context.getPath());
            if (!maybePair.isEmpty()) {
                return relocateMultiReleasePath(maybePair, context);
            }

            String output = super.relocatePath(context);
            log.debug("relocatePath('{}') -> {}", context.getPath(), output);
            return output;
        }

        private String relocateMultiReleasePath(List<String> pair, RelocatePathContext context) {
            RelocatePathContext newContext = new RelocatePathContext(pair.get(1));
            String out = pair.get(0) + super.relocatePath(newContext);
            log.debug("relocateMultiReleasePath('{}') -> {}", context.getPath(), out);
            return out;
        }

        @Override
        public String relocateClass(RelocateClassContext context) {
            String className = context.getClassName();
            String output;
            // Work around a poor interaction between ServiceFileTransformer and our
            // prefix configuration which otherwise results in prefixes being added
            // prior to 'META-INF', breaking service loading. The default SimpleRelocator
            // replaces the first instance of the expected prefix with the new prefix,
            // however this is problematic when the expected prefix is an empty string.
            if (className != null && className.startsWith(SERVICE_PROVIDER_PREFIX)) {
                String targetClassName = className.substring(SERVICE_PROVIDER_PREFIX.length());
                RelocateClassContext serviceContext = new RelocateClassContext(targetClassName);
                output = SERVICE_PROVIDER_PREFIX + super.relocateClass(serviceContext);
            } else {
                output = super.relocateClass(context);
            }
            log.debug("relocateClass('{}') -> {}", context.getClassName(), output);
            return output;
        }
    }
}
