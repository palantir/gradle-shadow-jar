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
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
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
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Originally taken from https://github.com/johnrengelman/shadow/blob/d4e649d7dd014bfdd9575bfec92d7e74c3cf1aca/
// src/main/groovy/com/github/jengelman/gradle/plugins/shadow/tasks/ConfigureShadowRelocation.groovy
public abstract class ShadowJarConfigurationTask extends DefaultTask {

    private static final Logger log = LoggerFactory.getLogger(ShadowJarConfigurationTask.class);

    private static final String CLASS_SUFFIX = ".class";

    // Multi-Release JAR Files are defined in https://openjdk.java.net/jeps/238
    private static final Pattern MULTIRELEASE_JAR_PREFIX = Pattern.compile("^META-INF/versions/\\d+/");

    private final Property<ShadowJar> shadowJarProperty =
            getProject().getObjects().property(ShadowJar.class);

    private final Property<String> prefix = getProject().getObjects().property(String.class);
    private final SetProperty<ResolvedDependency> acceptedDependencies =
            getProject().getObjects().setProperty(ResolvedDependency.class);

    @Internal
    public final Property<ShadowJar> getShadowJar() {
        return shadowJarProperty;
    }

    @Input
    public final Property<String> getPrefix() {
        return prefix;
    }

    @Classpath
    public final List<Configuration> getConfigurations() {
        return shadowJarProperty.get().getConfigurations();
    }

    @Input
    public final SetProperty<ResolvedDependency> getAcceptedDependencies() {
        return acceptedDependencies;
    }

    @TaskAction
    public final void run() {
        ShadowJar shadowJarTask = shadowJarProperty.get();

        shadowJarTask.getDependencyFilter().include(acceptedDependencies.get()::contains);

        FileCollection jars = shadowJarTask.getDependencyFilter().resolve(getConfigurations());

        Set<String> pathsInJars = jars.getFiles().stream()
                .flatMap(jar -> {
                    try (JarFile jarFile = new JarFile(jar)) {
                        return Collections.list(jarFile.entries()).stream()
                                .filter(entry -> !entry.isDirectory())
                                .map(ZipEntry::getName)
                                .peek(path -> log.debug("Jar '{}' contains entry '{}'", jar.getName(), path))
                                .peek(path -> Preconditions.checkState(
                                        !path.startsWith("/"), "Unexpected absolute path '%s' in jar '%s'", path, jar))
                                .collect(Collectors.toList())
                                .stream();
                    } catch (IOException e) {
                        throw new RuntimeException("Could not open jar file", e);
                    }
                })
                .collect(Collectors.toSet());

        // The Relocator is responsible for fixing the bytecode at callsites *and* filenames of .class files,
        // so we have to account for things _calling_ these weird multi-release classes.
        Set<String> multiReleaseStuff = pathsInJars.stream()
                .flatMap(input -> splitMultiReleasePath(input).stream().skip(1))
                .collect(Collectors.toSet());

        Set<String> relocatable = Stream.concat(pathsInJars.stream(), multiReleaseStuff.stream())
                .filter(path -> !path.equals("META-INF/MANIFEST.MF")) // don't relocate this!
                .collect(Collectors.toSet());

        shadowJarTask.relocate(new JarFilesRelocator(relocatable, prefix.get() + "."));

        if (!multiReleaseStuff.isEmpty()) {
            try {
                shadowJarTask.transform(ComposableManifestAppenderTransformer.class, transformer -> {
                    // JEP 238 requires this manifest entry
                    transformer.append("Multi-Release", true);
                });
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Unable to construct ManifestAppenderTransformer", e);
            }
        }
    }

    /** Returns a pair of 'META-INF/versions/9/' and 'com/foo/whatever.class'. */
    private static List<String> splitMultiReleasePath(String input) {
        Matcher matcher = MULTIRELEASE_JAR_PREFIX.matcher(input);
        if (matcher.find()) {
            return ImmutableList.of(input.substring(0, matcher.end()), input.substring(matcher.end()));
        } else {
            return ImmutableList.of();
        }
    }

    @CacheableRelocator
    private static final class JarFilesRelocator extends SimpleRelocator {
        private final Set<String> relocatable;

        private JarFilesRelocator(Set<String> relocatable, String shadedPrefix) {
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
            context.setPath(pair.get(1));
            String out = pair.get(0) + super.relocatePath(context);
            log.debug("relocateMultiReleasePath('{}') -> {}", context.getPath(), out);
            return out;
        }

        @Override
        public String relocateClass(RelocateClassContext context) {
            String output = super.relocateClass(context);
            log.debug("relocateClass('{}') -> {}", context.getClassName(), output);
            return output;
        }
    }
}
