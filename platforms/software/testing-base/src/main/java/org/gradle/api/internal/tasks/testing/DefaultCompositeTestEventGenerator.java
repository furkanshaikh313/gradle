/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.testing;

import com.google.common.collect.ImmutableSet;
import org.gradle.api.NonNullApi;
import org.gradle.api.tasks.testing.CompositeTestEventGenerator;
import org.gradle.api.tasks.testing.TestEventGenerator;
import org.gradle.internal.id.IdGenerator;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@NonNullApi
public class DefaultCompositeTestEventGenerator extends DefaultTestEventGenerator implements CompositeTestEventGenerator {
    private final IdGenerator<?> idGenerator;
    private Set<DefaultTestEventGenerator> children = new HashSet<>();

    public DefaultCompositeTestEventGenerator(
        TestResultProcessor processor, IdGenerator<?> idGenerator, @Nullable DefaultCompositeTestEventGenerator parent, TestDescriptorInternal testDescriptor
    ) {
        super(processor, parent, testDescriptor);
        this.idGenerator = idGenerator;
    }

    void removeChild(DefaultTestEventGenerator child) {
        children.remove(child);
    }

    @Override
    protected void cleanup() {
        List<Throwable> errors = null;
        // We must take a copy of the set here, as the close method may remove children from the set
        // Usually it should be empty, so there is likely little impact
        for (DefaultTestEventGenerator child : ImmutableSet.copyOf(children)) {
            // When we close children, try to close all of them, even if some fail
            // We'll throw all exceptions as a bundle at the end
            try {
                child.close();
            } catch (Error e) {
                // Let errors propagate
                if (errors != null) {
                    errors.forEach(e::addSuppressed);
                }
                throw e;
            } catch (Throwable t) {
                if (errors == null) {
                    errors = new ArrayList<>();
                }
                errors.add(t);
            }
        }
        if (errors != null) {
            RuntimeException e = new RuntimeException("Failed to close children");
            errors.forEach(e::addSuppressed);
            throw e;
        }
        children = null;
        super.cleanup();
    }

    @Override
    public TestEventGenerator createTestNode(String name, String displayName) {
        requireRunning();
        DefaultTestEventGenerator child = new DefaultTestEventGenerator(
            processor, this, new DefaultTestDescriptor(idGenerator.generateId(), null, name, null, displayName)
        );
        children.add(child);
        return child;
    }

    @Override
    public CompositeTestEventGenerator createCompositeNode(String name) {
        requireRunning();
        DefaultCompositeTestEventGenerator child = new DefaultCompositeTestEventGenerator(
            processor, idGenerator, this, new DefaultTestSuiteDescriptor(idGenerator.generateId(), name)
        );
        children.add(child);
        return child;
    }
}
