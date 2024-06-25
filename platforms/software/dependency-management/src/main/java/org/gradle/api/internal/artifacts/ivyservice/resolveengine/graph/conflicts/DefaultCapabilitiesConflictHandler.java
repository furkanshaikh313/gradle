/*
 * Copyright 2018 the original author or authors.
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
package org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.gradle.api.Action;
import org.gradle.api.Describable;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.builder.ComponentState;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.builder.NodeState;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.ComponentSelectionDescriptorInternal;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.ComponentSelectionReasons;
import org.gradle.api.internal.capabilities.CapabilityInternal;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultCapabilitiesConflictHandler implements CapabilitiesConflictHandler {
    private final List<Resolver> resolvers;

    /**
     * Tracks capabilities for which nodes exist with that capability as non-default
     */
    private final Map<String, Set<NodeState>> capabilityWithoutVersionToNodes = new HashMap<>();

    private final Deque<String> conflicts = new ArrayDeque<>();
    private final Map<String, CapabilityConflict> capabilityIdToConflict = new HashMap<>();

    public DefaultCapabilitiesConflictHandler(List<Resolver> resolvers) {
        this.resolvers = resolvers;
    }

    @Override
    public PotentialConflict registerCandidate(CapabilitiesConflictHandler.Candidate candidate) {
        CapabilityInternal capability = candidate.getCapability();

        Set<NodeState> nodes = capabilityWithoutVersionToNodes.computeIfAbsent(capability.getCapabilityId(), k -> new LinkedHashSet<>());
        Collection<NodeState> implicitCapabilityProviders = candidate.getImplicitCapabilityProviders();
        nodes.addAll(implicitCapabilityProviders);

        NodeState node = candidate.getNode();
        if (nodes.add(node) && nodes.size() > 1) {
            // If the root node is in conflict, find its module ID
            ModuleIdentifier rootId = null;
            for (NodeState ns : nodes) {
                if (ns.isRoot()) {
                    rootId = ns.getComponent().getId().getModule();
                }
            }

            Set<NodeState> candidatesForConflict = new LinkedHashSet<>(nodes);
            if (rootId != null && candidatesForConflict.size() > 1) {
                // This is a special case for backwards compatibility: it is possible to have
                // a cycle where the root component depends on a library which transitively
                // depends on a different version of the root module. In this case, we effectively
                // allow 2 modules to have the same capability, so we filter the nodes coming
                // from transitive dependencies
                ModuleIdentifier rootModuleId = rootId;
                candidatesForConflict.removeIf(n -> !n.isRoot() && n.getComponent().getId().getModule().equals(rootModuleId));
            }
            if (candidatesForConflict.size() > 1) {
                PotentialConflict conflict = new PotentialConflict() {
                    @Override
                    public void withParticipatingModules(Action<ModuleIdentifier> action) {
                        for (NodeState node : candidatesForConflict) {
                            action.execute(node.getComponent().getId().getModule());
                        }
                    }

                    @Override
                    public boolean conflictExists() {
                        return true;
                    }
                };

                String group = capability.getGroup();
                String name = capability.getName();
                if (capabilityIdToConflict.put(capability.getCapabilityId(), new CapabilityConflict(group, name, candidatesForConflict)) == null) {
                    // No previous conflict, enqueue the capability for resolution
                    conflicts.add(capability.getCapabilityId());
                }
                return conflict;
            }
        }
        return PotentialConflictFactory.noConflict();
    }

    @Override
    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }

    @Override
    public void resolveNextConflict(Action<ConflictResolutionResult> resolutionAction) {
        String capabilityInConflict = conflicts.remove();
        CapabilityConflict conflict = capabilityIdToConflict.remove(capabilityInConflict).withSelectedNodes();
        capabilityWithoutVersionToNodes.put(capabilityInConflict, conflict.nodes);
        if (conflict.nodes.isEmpty()) {
            return;
        }

        // TODO: What if, after we filter, we only have nodes with the default capability?
        // We should treat this conflict as a version conflict.

        Details details = new Details(conflict);
        for (Resolver resolver : resolvers) {
            resolver.resolve(details);
            if (details.hasResult()) {
                resolutionAction.execute(details);

                if (conflict.nodes.size() > 1) {
                    ComponentSelectionDescriptorInternal conflictResolution = ComponentSelectionReasons.CONFLICT_RESOLUTION;
                    if (details.reason != null) {
                        conflictResolution = conflictResolution.withDescription(details.reason);
                    }
                    details.getSelected().addCause(conflictResolution);
                }

                return;
            }
        }
    }

    @Override
    public boolean hasSeenNonDefaultCapabilityExplicitly(CapabilityInternal capability) {
        return capabilityWithoutVersionToNodes.containsKey(capability.getCapabilityId());
    }

    @Override
    public boolean hasKnownConflictFor(ModuleVersionIdentifier id) {
        if (conflicts.isEmpty()) {
            return false;
        }

        // TODO: Should this look at implicit capabilities? Or just look at group:name
        // Or, does this intend to see if a specific NODE is in conflict?

        return capabilityIdToConflict.values().stream()
            .flatMap(capability -> capability.nodes.stream())
            .anyMatch(node -> node.getComponent().getId().equals(id));
    }

    public static CapabilitiesConflictHandler.Candidate candidate(NodeState node, CapabilityInternal capability, Collection<NodeState> implicitCapabilityProviders) {
        return new Candidate(node, capability, implicitCapabilityProviders);
    }

    private static class Candidate implements CapabilitiesConflictHandler.Candidate {
        private final NodeState node;
        private final CapabilityInternal capability;
        private final Collection<NodeState> implicitCapabilityProviders;

        public Candidate(NodeState node, CapabilityInternal capability, Collection<NodeState> implicitCapabilityProviders) {
            this.node = node;
            this.capability = capability;
            this.implicitCapabilityProviders = implicitCapabilityProviders;
        }

        @Override
        public NodeState getNode() {
            return node;
        }

        @Override
        public CapabilityInternal getCapability() {
            return capability;
        }

        @Override
        public Collection<NodeState> getImplicitCapabilityProviders() {
            return implicitCapabilityProviders;
        }
    }


    private static class Details implements ResolutionDetails {
        private final CapabilityConflict conflict;
        private final Set<NodeState> evicted = new HashSet<>();
        private NodeState selected;
        private Describable reason;

        private Details(CapabilityConflict conflict) {
            this.conflict = conflict;
        }

        @Override
        public Collection<? extends Capability> getCapabilityVersions() {
            return conflict.descriptors;
        }

        @Override
        public Collection<? extends CandidateDetails> getCandidates(Capability capability) {
            ImmutableList.Builder<CandidateDetails> candidates = new ImmutableList.Builder<>();
            String group = capability.getGroup();
            String name = capability.getName();
            String version = capability.getVersion();
            for (final NodeState node : conflict.nodes) {
                if (!evicted.contains(node)) {
                    Capability componentCapability = node.findCapability(group, name);
                    if (componentCapability != null && componentCapability.getVersion().equals(version)) {
                        candidates.add(new CandidateDetails() {
                            @Override
                            public ComponentIdentifier getId() {
                                return node.getComponent().getComponentId();
                            }

                            @Override
                            public String getVariantName() {
                                return node.getMetadata().getName();
                            }

                            @Override
                            public void evict() {
                                node.evict();
                                evicted.add(node);
                            }

                            @Override
                            public void select() {
                                selected = node;
                            }

                            @Override
                            public void reject() {
                                ComponentState component = node.getComponent();
                                component.rejectForCapabilityConflict(capability, conflictedNodes(node, conflict.nodes));

                                // TODO: Determine if we really need to select this rejected node.
                                // How do we prevent the node from selecting its own dependencies when it
                                // gets added to the graph?
                                // Conceptually, it seems odd for a module to select a node that does not belong in the graph
                                // however, this seems to be required currently for the sake of reporting the conflict
                                // final graph visiting.
                                component.selectAndRestartModule();
                            }

                            @Override
                            public void byReason(Describable description) {
                                reason = description;
                            }
                        });
                    }
                }
            }
            return candidates.build();
        }

        private Collection<NodeState> conflictedNodes(NodeState node, Collection<NodeState> nodes) {
            List<NodeState> conflictedNodes = Lists.newArrayList(nodes);
            conflictedNodes.remove(node);
            return conflictedNodes;
        }

        @Override
        public void withParticipatingModules(Action<? super ModuleIdentifier> action) {
            Set<ModuleIdentifier> seen = new HashSet<>();
            for (NodeState node : conflict.nodes) {
                ModuleIdentifier module = node.getComponent().getId().getModule();
                if (seen.add(module)) {
                    action.execute(module);
                }
            }
        }

        @Override
        public boolean hasResult() {
            return selected != null;
        }

        @Override
        public ComponentState getSelected() {
            return selected.getComponent();
        }
    }

    private static class CapabilityConflict {

        private final String group;
        private final String name;

        private final Set<NodeState> nodes;
        private final Set<Capability> descriptors;

        private CapabilityConflict(String group, String name, Set<NodeState> nodes) {
            this.group = group;
            this.name = name;
            this.nodes = nodes;
            final ImmutableSet.Builder<Capability> builder = new ImmutableSet.Builder<>();
            for (final NodeState node : nodes) {
                Capability capability = node.findCapability(group, name);
                if (capability != null) {
                    builder.add(capability);
                }
            }
            this.descriptors = builder.build();
        }

        /**
         * Many things can happen in-between detection of a conflict and resolution of the conflict. By the time
         * we resolve the conflict, some or all of the nodes that originally conflicted may no longer be present
         * in the graph.
         *
         * @return a new conflict containing only nodes that are still present in the graph. This new conflict
         * may contain no nodes.
         */
        public CapabilityConflict withSelectedNodes() {
            Set<NodeState> selectedNodes = new LinkedHashSet<>();
            for (NodeState node : nodes) {
                if (node.isSelected()) {
                    selectedNodes.add(node);
                }
            }
            return new CapabilityConflict(group, name, selectedNodes);
        }

    }
}
