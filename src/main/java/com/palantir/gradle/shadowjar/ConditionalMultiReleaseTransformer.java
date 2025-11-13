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

import com.github.jengelman.gradle.plugins.shadow.transformers.CacheableTransformer;
import com.github.jengelman.gradle.plugins.shadow.transformers.ResourceTransformer;
import com.github.jengelman.gradle.plugins.shadow.transformers.TransformerContext;
import com.google.common.base.Suppliers;
import java.util.function.Supplier;
import org.apache.tools.zip.ZipOutputStream;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.provider.Provider;

/**
 * A transformer that conditionally adds Multi-Release manifest attribute based on
 * whether any of the input jars contain multi-release versioned classes.
 */
@CacheableTransformer
public final class ConditionalMultiReleaseTransformer implements ResourceTransformer {
    private final ComposableManifestAppenderTransformer delegate = new ComposableManifestAppenderTransformer();
    private final Supplier<Boolean> shouldTransformSupplier;

    public ConditionalMultiReleaseTransformer(Provider<Boolean> hasMultiReleaseProvider) {
        this.shouldTransformSupplier = Suppliers.memoize(() -> {
            if (hasMultiReleaseProvider.get()) {
                delegate.append("Multi-Release", true);
            }
            return hasMultiReleaseProvider.get();
        });
    }

    @Override
    public boolean canTransformResource(FileTreeElement element) {
        return shouldTransformSupplier.get() && delegate.canTransformResource(element);
    }

    @Override
    public void transform(TransformerContext context) {
        if (shouldTransformSupplier.get()) {
            delegate.transform(context);
        }
    }

    @Override
    public boolean hasTransformedResource() {
        return shouldTransformSupplier.get() && delegate.hasTransformedResource();
    }

    @Override
    public void modifyOutputStream(ZipOutputStream os, boolean preserveFileTimestamps) {
        if (shouldTransformSupplier.get()) {
            delegate.modifyOutputStream(os, preserveFileTimestamps);
        }
    }
}
