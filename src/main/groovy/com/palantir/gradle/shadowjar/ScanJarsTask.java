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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans JARs at execution time to determine which paths should be relocated.
 * This task is configuration-cache compatible and runs lazily.
 */
@CacheableTask
public abstract class ScanJarsTask extends DefaultTask {

    private static final Logger log = LoggerFactory.getLogger(ScanJarsTask.class);
    private static final ObjectMapper OBJECT_MAPPER =
            JsonMapper.builder(new JsonFactory()).build();
    private static final Pattern MULTIRELEASE_JAR_PREFIX = Pattern.compile("^META-INF/versions/\\d+/");
    private static final String SERVICE_PROVIDER_PREFIX = "META-INF/services/";

    /**
     * Set of JAR files to scan (from accepted modules only).
     */
    @Classpath
    public abstract ConfigurableFileCollection getJarsToScan();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public final void scanJars() throws IOException {
        Set<File> jarsToScan = getJarsToScan().getFiles();

        Set<String> pathsInJars = jarsToScan.stream()
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
                        throw new RuntimeException("Could not open jar file: " + jar, e);
                    }
                })
                .collect(Collectors.toSet());

        // Process paths into RelocationData
        Set<String> multiReleaseStuff = pathsInJars.stream()
                .flatMap(input -> splitMultiReleasePath(input).stream().skip(1))
                .collect(Collectors.toSet());

        Set<String> relocatable = Stream.concat(pathsInJars.stream(), multiReleaseStuff.stream())
                .filter(path -> !path.equals("META-INF/MANIFEST.MF"))
                .filter(path -> !path.startsWith(SERVICE_PROVIDER_PREFIX))
                .collect(Collectors.toSet());

        RelocationData relocationData = new RelocationData(relocatable, !multiReleaseStuff.isEmpty());

        // Write as JSON
        File outputFile = getOutputFile().get().getAsFile();
        outputFile.getParentFile().mkdirs();
        OBJECT_MAPPER.writeValue(outputFile, relocationData);

        log.info(
                "Scanned {} JAR files and found {} unique paths",
                jarsToScan.size(),
                pathsInJars.size());
    }

    /**
     * Returns a provider that reads the RelocationData from the JSON output file.
     * This encapsulates the serialization format.
     */
    public static Provider<RelocationData> getRelocationData(Provider<RegularFile> outputFile) {
        return outputFile.map(file -> {
            try {
                return OBJECT_MAPPER.readValue(file.getAsFile(), RelocationData.class);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read jar scan results from JSON", e);
            }
        });
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
}
