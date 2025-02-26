package io.github.jbellis.brokk;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.ContextFragment.AutoContext;
import io.github.jbellis.brokk.ContextFragment.SkeletonFragment;
import io.github.jbellis.brokk.ContextFragment.StacktraceFragment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Encapsulates all state that will be sent to the model (prompts, filename context, conversation history).
 */
public class Context {
    public static final int MAX_AUTO_CONTEXT_FILES = 100;

    final AnalyzerWrapper analyzer;
    final List<ContextFragment.RepoPathFragment> editableFiles;
    final List<ContextFragment.PathFragment> readonlyFiles;
    final List<ContextFragment.VirtualFragment> virtualFragments;

    final AutoContext autoContext;
    final int autoContextFileCount;
    /** history messages are unusual because they are updated in place.  See comments to addHistory and clearHistory */
    final List<ChatMessage> historyMessages;

    /** backup of original contents for /undo, does not carry forward to Context children */
    final Map<RepoFile, String> originalContents;

    /**
     * Default constructor, with empty files/fragments and autoContext on, and a default of 5 files.
     */
    public Context(AnalyzerWrapper analyzer, int autoContextFileCount) {
        this(analyzer, List.of(), List.of(), List.of(), AutoContext.EMPTY, autoContextFileCount, new ArrayList<>(), Map.of());
    }

    public Context(
            AnalyzerWrapper analyzer,
            List<ContextFragment.RepoPathFragment> editableFiles,
            List<ContextFragment.PathFragment> readonlyFiles,
            List<ContextFragment.VirtualFragment> virtualFragments,
            AutoContext autoContext,
            int autoContextFileCount,
            List<ChatMessage> historyMessages,
            Map<RepoFile, String> originalContents
    ) {
        assert analyzer != null;
        assert autoContext != null;
        assert autoContextFileCount >= 0;
        this.analyzer = analyzer;
        this.editableFiles = List.copyOf(editableFiles);
        this.readonlyFiles = List.copyOf(readonlyFiles);
        this.virtualFragments = List.copyOf(virtualFragments);
        this.autoContext = autoContext;
        this.autoContextFileCount = autoContextFileCount;
        this.historyMessages = historyMessages;
        this.originalContents = originalContents;
    }

    /**
     * Creates a new Context with an additional set of editable files. Rebuilds autoContext if toggled on.
     */
    public Context addEditableFiles(Collection<ContextFragment.RepoPathFragment> paths) {
        var toAdd = paths.stream().filter(fragment -> !editableFiles.contains(fragment)).toList();
        if (toAdd.isEmpty()) {
            return this;
        }
        var newEditable = new ArrayList<>(editableFiles);
        newEditable.addAll(toAdd);
        return withEditableFiles(newEditable).refresh();
    }

    public Context addReadonlyFiles(Collection<ContextFragment.PathFragment> paths) {
        var toAdd = paths.stream().filter(fragment -> !readonlyFiles.contains(fragment)).toList();
        if (toAdd.isEmpty()) {
            return this;
        }
        List<ContextFragment.PathFragment> newReadOnly = new ArrayList<>(readonlyFiles);
        newReadOnly.addAll(paths);
        return withReadonlyFiles(newReadOnly).refresh();
    }

    public Context removeEditableFile(ContextFragment.PathFragment fragment) {
        return removeEditableFiles(List.of(fragment));
    }

    public Context removeEditableFiles(List<ContextFragment.PathFragment> fragments) {
        var newEditable = new ArrayList<>(editableFiles);
        newEditable.removeAll(fragments);
        if (newEditable.equals(editableFiles)) {
            return this;
        }
        return withEditableFiles(newEditable).refresh();
    }

    public Context removeReadonlyFiles(List<? extends ContextFragment.PathFragment> fragments) {
        List<ContextFragment.PathFragment> newReadOnly = new ArrayList<>(readonlyFiles);
        newReadOnly.removeAll(fragments);
        if (newReadOnly.equals(readonlyFiles)) {
            return this;
        }
        return withReadonlyFiles(newReadOnly).refresh();
    }

    public Context removeVirtualFragments(List<? extends ContextFragment.VirtualFragment> fragments) {
        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.removeAll(fragments);
        if (newFragments.equals(virtualFragments)) {
            return this;
        }
        return withVirtualFragments(newFragments).refresh();
    }
    
    public Context removeReadonlyFile(ContextFragment.PathFragment path) {
        return removeReadonlyFiles(List.of(path));
    }

    public Context addStringFragment(String description, String content) {
        var fragment = new ContextFragment.StringFragment(content, description);
        return addVirtualFragment(fragment);
    }

    public Context addStacktraceFragment(Set<CodeUnit> sources, String original, String exception, String methods) {
        var fragment = new StacktraceFragment(sources, original, exception, methods);
        return addVirtualFragment(fragment);
    }

    public Context addPasteFragment(String content, Future<String> descriptionFuture) {
        var fragment = new ContextFragment.PasteFragment(content, descriptionFuture);
        return addVirtualFragment(fragment);
    }

    private Context addVirtualFragment(ContextFragment.VirtualFragment fragment) {
        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.add(fragment);
        return withVirtualFragments(newFragments).refresh();
    }

    public Context convertAllToReadOnly() {
        List<ContextFragment.PathFragment> newReadOnly = new ArrayList<>(readonlyFiles);
        newReadOnly.addAll(editableFiles);
        return withEditableFiles(List.of())
                .withReadonlyFiles(newReadOnly);
    }

    public Context removeBadFragment(ContextFragment f) {
        if (f instanceof ContextFragment.PathFragment pf) {
            Context tmp = removeEditableFile(pf);
            if (tmp == this) {
                tmp = removeReadonlyFile(pf);
            }
            return tmp.refresh();
        } else if (f instanceof ContextFragment.VirtualFragment vf) {
            return removeVirtualFragments(List.of(vf));
        } else {
            throw new IllegalArgumentException("Unknown fragment type: " + f);
        }
    }

    /**
     * Sets how many files are pulled out of pagerank results. Uses 2*n in the pagerank call to
     * account for any out-of-project exclusions. Rebuilds autoContext if toggled on.
     */
    public Context setAutoContextFiles(int fileCount) {
        return withAutoContextFileCount(fileCount).refresh();
    }

    /**
     * 1) Gather all classes from each fragment.
     * 2) Compute PageRank with those classes as seeds, requesting up to 2*MAX_AUTO_CONTEXT_FILES
     * 3) Build a multiline skeleton text for the top autoContextFileCount results
     * 4) Return the new AutoContext instance
     */
    private AutoContext buildAutoContext() {
        if (!isAutoContextEnabled()) {
            return AutoContext.DISABLED;
        }
        
        // Collect ineligible classnames from fragments not eligible for auto-context
        var ineligibleSources = Streams.concat(editableFiles.stream(), readonlyFiles.stream(), virtualFragments.stream())
                .filter(f -> !f.isEligibleForAutoContext())
                .flatMap(f -> f.sources(analyzer.get()).stream())
                .collect(Collectors.toSet());

        // Collect initial seeds
        var weightedSeeds = new HashMap<String, Double>();
        // editable files have a weight of 1.0, each
        editableFiles.stream().flatMap(f -> f.sources(analyzer.get()).stream()).forEach(unit -> {
            weightedSeeds.put(unit.reference(), 1.0);
        });
        // everything else splits a weight of 1.0
        Streams.concat(readonlyFiles.stream(), virtualFragments.stream())
                .flatMap(f -> f.sources(analyzer.get()).stream())
                .forEach(unit ->
        {
            weightedSeeds.merge(unit.reference(), 1.0 / (readonlyFiles.size() + virtualFragments.size()), Double::sum);
        });

        // If no seeds, we can't compute pagerank
        if (weightedSeeds.isEmpty()) {
            return AutoContext.EMPTY;
        }

        var pagerankResults = AnalyzerWrapper.combinedPageRankFor(analyzer.get(), weightedSeeds);

        // build skeleton lines
        var skeletons = new ArrayList<SkeletonFragment>();
        for (var fqName : pagerankResults) {
            // Check if the class or its parent is in ineligible classnames
            boolean eligible = !(ineligibleSources.contains(CodeUnit.cls(fqName))
                    || (fqName.contains("$") && ineligibleSources.contains(CodeUnit.cls(fqName.substring(0, fqName.indexOf('$'))))));
            
            if (eligible && analyzer.get().isClassInProject(fqName)) {
                var opt = analyzer.get().getSkeleton(fqName);
                if (opt.isDefined()) {
                    var shortName = fqName.substring(fqName.lastIndexOf('.') + 1);
                    skeletons.add(new SkeletonFragment(List.of(shortName), Set.of(CodeUnit.cls(fqName)), opt.get()));
                }
            }
            if (skeletons.size() >= autoContextFileCount) {
                break;
            }
        }
        if (skeletons.isEmpty()) {
            return AutoContext.EMPTY;
        }

        return new AutoContext(skeletons);
    }

    // ---------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------

    public Stream<ContextFragment.RepoPathFragment> editableFiles() {
        return editableFiles.stream();
    }

    public Stream<ContextFragment.PathFragment> readonlyFiles() {
        return readonlyFiles.stream();
    }

    public Stream<ContextFragment.VirtualFragment> virtualFragments() {
        return virtualFragments.stream();
    }

    public Stream<? extends ContextFragment> allFragments() {
        return Streams.concat(editableFiles.stream(),
                              readonlyFiles.stream(),
                              virtualFragments.stream());
    }

    public boolean hasReadonlyFragments() {
        return !readonlyFiles.isEmpty() || !virtualFragments.isEmpty();
    }

    public boolean isAutoContextEnabled() {
        return autoContextFileCount > 0;
    }

    public AutoContext getAutoContext() {
        return autoContext;
    }

    public int getAutoContextFileCount() {
        return autoContextFileCount;
    }

    private Context withEditableFiles(List<ContextFragment.RepoPathFragment> newEditableFiles) {
        return new Context(analyzer, newEditableFiles, readonlyFiles, virtualFragments, autoContext, autoContextFileCount, historyMessages, Map.of());
    }

    private Context withReadonlyFiles(List<ContextFragment.PathFragment> newReadonlyFiles) {
        return new Context(analyzer, editableFiles, newReadonlyFiles, virtualFragments, autoContext, autoContextFileCount, historyMessages, Map.of());
    }

    private Context withVirtualFragments(List<ContextFragment.VirtualFragment> newVirtualFragments) {
        return new Context(analyzer, editableFiles, readonlyFiles, newVirtualFragments, autoContext, autoContextFileCount, historyMessages, Map.of());
    }

    private Context withAutoContextFileCount(int newAutoContextFileCount) {
        return new Context(analyzer, editableFiles, readonlyFiles, virtualFragments, autoContext, newAutoContextFileCount, historyMessages, Map.of());
    }

    public Context removeAll() {
        return withEditableFiles(List.of())
                .withReadonlyFiles(List.of())
                .withVirtualFragments(List.of())
                .refresh();
    }

    /**
     * Produces a new Context object with a fresh AutoContext if enabled.
     */
    public Context refresh() {
        AutoContext newAutoContext = isAutoContextEnabled() ? buildAutoContext() : AutoContext.DISABLED;
        return new Context(analyzer, editableFiles, readonlyFiles, virtualFragments, newAutoContext, autoContextFileCount, historyMessages, Map.of());
    }

    /**
     * Return the String or Path Fragment corresponding to the given target,
     * or null if not found.
     */
    public ContextFragment toFragment(String target) {
        try {
            int ordinal = Integer.parseInt(target);
            if (ordinal == 0) {
                if (!isAutoContextEnabled()) {
                    return null;
                }
                return autoContext;
            }

            if (ordinal > virtualFragments.size() || ordinal < 1) {
                return null;
            }
            return virtualFragments.get(ordinal - 1);
        } catch (NumberFormatException e) {
            return Streams.concat(editableFiles.stream(), readonlyFiles.stream())
                    .filter(f -> f.source(this).equals(target))
                    .findFirst()
                    .orElse(null);
        }
    }

    public boolean isEmpty() {
        return editableFiles.isEmpty()
            && readonlyFiles.isEmpty()
            && virtualFragments.isEmpty()
            && historyMessages.isEmpty();
    }

    /**
     * Adding to the history DOES NOT create a new context object, it modifies history messages in place
     * for this and any other contexts that share the same history instance.
     * Otherwise popping context off with /undo
     * would clear out the most recent conversation round trip which is not what we want.
     */
    public Context addHistory(List<ChatMessage> newMessages, Map<RepoFile, String> originalContents) {
        var newHistory = new ArrayList<>(historyMessages);
        newHistory.addAll(newMessages);
        return new Context(
            analyzer,
            editableFiles,
            readonlyFiles,
            virtualFragments,
            autoContext,
            autoContextFileCount,
            List.copyOf(newHistory),
            originalContents
        );
    }

    /**
     * Clearing the history DOES create a new context object so you can /undo it
     */
    public Context clearHistory() {
        return new Context(
            analyzer,
            editableFiles,
            readonlyFiles,
            virtualFragments,
            autoContext,
            autoContextFileCount,
            List.of(),
            Map.of()
        );
    }

    public Context withOriginalContents(Map<RepoFile, String> fileContents) {
        return new Context(
                analyzer,
                editableFiles,
                readonlyFiles,
                virtualFragments,
                autoContext,
                autoContextFileCount,
                historyMessages,
                fileContents
        );
    }

    /**
     * @return an immutable copy of the history messages
     */
    public List<ChatMessage> getHistory() {
        return List.copyOf(historyMessages);
    }

    public boolean hasEditableFiles() {
        return !editableFiles.isEmpty();
    }

    public Context addUsageFragment(String identifier, Set<CodeUnit> classnames, String code) {
        var fragment = new ContextFragment.UsageFragment(identifier, classnames, code);
        return addVirtualFragment(fragment);
    }

    public Context addSkeletonFragment(List<String> shortClassnames, Set<CodeUnit> classnames, String skeleton) {
        var fragment = new SkeletonFragment(shortClassnames, classnames, skeleton);
        return addVirtualFragment(fragment);
    }

    /**
     * Returns the position (index) of the given fragment in the virtual fragments list.
     * Returns -1 if the fragment is not in the list.
     */
    public int getPositionOfFragment(ContextFragment.VirtualFragment fragment) {
        return virtualFragments.indexOf(fragment);
    }
}
