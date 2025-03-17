package io.github.jbellis.brokk.git;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.ignore.IgnoreNode.MatchResult;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public class GitIgnore {
    private static final Logger logger = LogManager.getLogger(GitIgnore.class);
    private static final String BROKK_DIR = ".brokk/**";
    private static final String STYLE_MD = ".brokk/style.md";
    private static final String PROJECT_PROPERTIES = ".brokk/project.properties";
    private static final String AUTOSET_IGNORE_PATTERNS_MSG = "### BROKK'S AUTO-SET PATTERNS ###";

    /**
     * Uses the {@code path} to search for ".gitignore" file.
     *
     * <p>Extracts its ignore rules and then validates whether there are any mentions of ".brokk/",
     * ".brokk/style.md", ".brokk/project.properties".
     *
     * <pre>
     * If non, an ignore rule will be appended to the end of the file
     * If there are any, whether the rule ignores the file/dir or makes an exception, 
     * no rule will be appended
     * </pre>
     *
     * <p>The ".gitignore" file will be created, whether any rule is added or not.
     *
     * @param path to search for .gitignore
     * @throws IOException if there are problems with reading or creating ".gitignore" file at the
     *     {@code path}
     */
    public static void setRulesIgnoreConfigFiles(Path path) throws IOException {
        Objects.requireNonNull(path, "The root cannot be null");

        var gitignore = new File(path.toFile(), ".gitignore");

        IgnoreNode ignoreNode = new IgnoreNode();
        // reading the .gitignore file and extracting all the ignore rules
        try (var in = new FileInputStream(gitignore)) {
            ignoreNode.parse(in);
        }

        // ignore or not ignore rules to be written to .gitignore files
        var ignoreRules = new StringBuilder();

        var optIgnoreRuleBrokkDir = getIgnoreRule(ignoreNode, BROKK_DIR, true, true);
        if (optIgnoreRuleBrokkDir.isPresent()) {
            ignoreRules.append(optIgnoreRuleBrokkDir.get());
        }

        var optIgnoreStyleMD = getIgnoreRule(ignoreNode, STYLE_MD, false, false);
        if (optIgnoreStyleMD.isPresent()) {
            if (!ignoreRules.isEmpty()) {
                ignoreRules.append(System.lineSeparator());
            }

            ignoreRules.append(optIgnoreStyleMD.get());
        }

        var optIgnoreProjectProps = getIgnoreRule(ignoreNode, PROJECT_PROPERTIES, false, false);
        if (optIgnoreProjectProps.isPresent()) {
            if (!ignoreRules.isEmpty()) {
                ignoreRules.append(System.lineSeparator());
            }

            ignoreRules.append(optIgnoreProjectProps.get());
        }

        if (ignoreRules.isEmpty()) {
            return;
        }

        try (var out = new BufferedWriter(new FileWriter(gitignore, true))) {
            out.write(
                    System.lineSeparator() + AUTOSET_IGNORE_PATTERNS_MSG + System.lineSeparator());
            out.write(ignoreRules.toString());
            out.flush();
        }

        logger.info(
                "Ignore rules have been added to the '.gitignore': %s"
                        .formatted(ignoreRules.toString().replace(System.lineSeparator(), " ")));
    }

    /**
     * Checks against {@code node} whether the {@code resource} is ignored or not
     *
     * <pre>
     *  If no ignore rules are provided against the {@code resource} by {@code node} an ignore rule is returned
     *  If an ignore or not to ignore rule is explicitly stated, no ignore rule will be returned
     *
     *  If {@code ignore == true} ignore rule will not be changed
     *  Else ignore rule prepand an "!" to the resource (i.e ".brokk/style.md" -> "!.brokk/style.md")
     * </pre>
     *
     * @param node to check {@code resource} against
     * @param resource to check whether no ignore rules are provided by {@code node}
     * @param ignore whether to return an ignore or exclude the resource statement ( i.e.
     *     "!resource" excluding if false)
     * @param isDirectory whether the resource is directory, used by {@link
     *     IgnoreNode#isMatch(String, boolean)}
     * @return an ignore rule if {@code node} has any rules against the {@code resource}
     */
    private static Optional<String> getIgnoreRule(
            IgnoreNode node, String resource, boolean ignore, boolean isDirectory) {
        Objects.requireNonNull(node);
        Objects.requireNonNull(resource);

        var rules = node.getRules();

        // not using IgnoreNode.isIgnore() because on
        // on no pattern match, it will return NOT_IGNORED or IGNORED (depending on negation)
        // whereas we need to know whether we have an explicit match at all
        var result = MatchResult.CHECK_PARENT;
        for (var rule : rules) {
            if (rule.isMatch(resource, isDirectory)) {
                if (rule.getNegation()) {
                    result = MatchResult.NOT_IGNORED;
                } else {
                    result = MatchResult.IGNORED;
                }
            }
        }

        return switch (result) {
            // no rule is defined, so add an ignore rule
            case CHECK_PARENT -> {
                if (!ignore) resource = "!" + resource;

                yield Optional.of(resource);
            }
            // it's ignored already or not ignored explicitly, so do nothing
            default -> Optional.empty();
        };
    }
}
