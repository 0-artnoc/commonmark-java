package org.commonmark.internal;

import org.commonmark.internal.inline.Position;
import org.commonmark.internal.inline.Scanner;
import org.commonmark.internal.util.Escaping;
import org.commonmark.internal.util.LinkScanner;
import org.commonmark.node.LinkReferenceDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for link reference definitions at the beginning of a paragraph.
 *
 * @see <a href="https://spec.commonmark.org/0.29/#link-reference-definition">Link reference definitions</a>
 */
public class LinkReferenceDefinitionParser {

    private State state = State.START_DEFINITION;

    private final List<CharSequence> paragraphLines = new ArrayList<>();
    private final List<LinkReferenceDefinition> definitions = new ArrayList<>();

    private StringBuilder label;
    private String normalizedLabel;
    private String destination;
    private char titleDelimiter;
    private StringBuilder title;
    private boolean referenceValid = false;

    public void parse(CharSequence line) {
        paragraphLines.add(line);
        if (state == State.PARAGRAPH) {
            // We're in a paragraph now. Link reference definitions can only appear at the beginning, so once
            // we're in a paragraph, there's no going back.
            return;
        }

        Scanner scanner = Scanner.of(line);
        while (scanner.hasNext()) {
            boolean success;
            switch (state) {
                case START_DEFINITION: {
                    success = startDefinition(scanner);
                    break;
                }
                case LABEL: {
                    success = label(scanner);
                    break;
                }
                case DESTINATION: {
                    success = destination(scanner);
                    break;
                }
                case START_TITLE: {
                    success = startTitle(scanner);
                    break;
                }
                case TITLE: {
                    success = title(scanner);
                    break;
                }
                default: {
                    throw new IllegalStateException("Unknown parsing state: " + state);
                }
            }
            // Parsing failed, which means we fall back to treating text as a paragraph.
            if (!success) {
                state = State.PARAGRAPH;
                return;
            }
        }
    }

    /**
     * @return the lines that are normal paragraph content, without newlines
     */
    List<CharSequence> getParagraphLines() {
        return paragraphLines;
    }

    List<LinkReferenceDefinition> getDefinitions() {
        finishReference();
        return definitions;
    }

    State getState() {
        return state;
    }

    private boolean startDefinition(Scanner scanner) {
        scanner.whitespace();
        if (!scanner.next('[')) {
            return false;
        }

        state = State.LABEL;
        label = new StringBuilder();

        if (!scanner.hasNext()) {
            label.append('\n');
        }
        return true;
    }

    private boolean label(Scanner scanner) {
        Position start = scanner.position();
        if (!LinkScanner.scanLinkLabelContent(scanner)) {
            return false;
        }

        label.append(scanner.textBetween(start, scanner.position()));

        if (!scanner.hasNext()) {
            // label might continue on next line
            label.append('\n');
            return true;
        } else if (scanner.next(']')) {
            // end of label
            if (!scanner.next(':')) {
                return false;
            }

            // spec: A link label can have at most 999 characters inside the square brackets.
            if (label.length() > 999) {
                return false;
            }

            String normalizedLabel = Escaping.normalizeLabelContent(label.toString());
            if (normalizedLabel.isEmpty()) {
                return false;
            }

            this.normalizedLabel = normalizedLabel;
            state = State.DESTINATION;

            scanner.whitespace();
            return true;
        } else {
            return false;
        }
    }

    private boolean destination(Scanner scanner) {
        scanner.whitespace();
        Position start = scanner.position();
        if (!LinkScanner.scanLinkDestination(scanner)) {
            return false;
        }

        String rawDestination = scanner.textBetween(start, scanner.position()).toString();
        destination = rawDestination.startsWith("<") ?
                rawDestination.substring(1, rawDestination.length() - 1) :
                rawDestination;

        int whitespace = scanner.whitespace();
        if (!scanner.hasNext()) {
            // Destination was at end of line, so this is a valid reference for sure (and maybe a title).
            // If not at end of line, wait for title to be valid first.
            referenceValid = true;
            paragraphLines.clear();
        } else if (whitespace == 0) {
            // spec: The title must be separated from the link destination by whitespace
            return false;
        }

        state = State.START_TITLE;
        return true;
    }

    private boolean startTitle(Scanner scanner) {
        scanner.whitespace();
        if (!scanner.hasNext()) {
            state = State.START_DEFINITION;
            return true;
        }

        titleDelimiter = '\0';
        char c = scanner.peek();
        switch (c) {
            case '"':
            case '\'':
                titleDelimiter = c;
                break;
            case '(':
                titleDelimiter = ')';
                break;
        }

        if (titleDelimiter != '\0') {
            state = State.TITLE;
            title = new StringBuilder();
            scanner.next();
            if (!scanner.hasNext()) {
                title.append('\n');
            }
        } else {
            finishReference();
            // There might be another reference instead, try that for the same character.
            state = State.START_DEFINITION;
        }
        return true;
    }

    private boolean title(Scanner scanner) {
        Position start = scanner.position();
        if (!LinkScanner.scanLinkTitleContent(scanner, titleDelimiter)) {
            // Invalid title, stop
            return false;
        }

        title.append(scanner.textBetween(start, scanner.position()));

        if (!scanner.hasNext()) {
            // Title ran until the end of line, so continue on next line (until we find the delimiter)
            title.append('\n');
            return true;
        }

        // Skip delimiter character
        scanner.next();
        scanner.whitespace();
        if (scanner.hasNext()) {
            // spec: No further non-whitespace characters may occur on the line.
            return false;
        }
        referenceValid = true;
        finishReference();
        paragraphLines.clear();

        // See if there's another definition.
        state = State.START_DEFINITION;
        return true;
    }

    private void finishReference() {
        if (!referenceValid) {
            return;
        }

        String d = Escaping.unescapeString(destination);
        String t = title != null ? Escaping.unescapeString(title.toString()) : null;
        definitions.add(new LinkReferenceDefinition(normalizedLabel, d, t));

        label = null;
        referenceValid = false;
        normalizedLabel = null;
        destination = null;
        title = null;
    }

    enum State {
        // Looking for the start of a definition, i.e. `[`
        START_DEFINITION,
        // Parsing the label, i.e. `foo` within `[foo]`
        LABEL,
        // Parsing the destination, i.e. `/url` in `[foo]: /url`
        DESTINATION,
        // Looking for the start of a title, i.e. the first `"` in `[foo]: /url "title"`
        START_TITLE,
        // Parsing the content of the title, i.e. `title` in `[foo]: /url "title"`
        TITLE,

        // End state, no matter what kind of lines we add, they won't be references
        PARAGRAPH,
    }
}
