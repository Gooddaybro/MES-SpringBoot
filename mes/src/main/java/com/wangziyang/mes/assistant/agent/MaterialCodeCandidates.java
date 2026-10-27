package com.wangziyang.mes.assistant.agent;

import com.wangziyang.mes.assistant.AssistantResponses;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Complete original tokens only. Never normalize, repair or match a code substring. */
public final class MaterialCodeCandidates {
    private static final String DELIMITERS = "\\s，。；：、！？（）【】“”‘’;:!?()\\[\\]\\\"',";
    private static final Pattern TOKEN = Pattern.compile("[^\\p{IsHan}" + DELIMITERS + "]+");
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");
    private static final Pattern OUTER_WHITESPACE = Pattern.compile("^\\s+|\\s+$");
    private static final String ENGLISH_ROUTE_PREFIX = "(?:(?:show|query|get|check|find)\\s+)?(?:the\\s+)?(?:material\\s+)?(?:routing|route|operations?)";
    private static final Pattern MISSING_ENGLISH_CODE = Pattern.compile("(?i)^" + ENGLISH_ROUTE_PREFIX + "(?:[" + DELIMITERS + "]+for)?[" + DELIMITERS + "]*$");
    // Strip language only in a complete known grammar. Words in its code slot remain codes,
    // including "go", "made", "for", etc. Unknown mixed phrases fail closed as multiple tokens.
    private static final Pattern[] ENGLISH_QUESTION = {
            Pattern.compile("(?i)^" + ENGLISH_ROUTE_PREFIX + "\\s+(?:for\\s+)?(.+)$"),
            Pattern.compile("(?i)^how\\s+is\\s+(.+?)\\s+made[?!.]*$"),
            Pattern.compile("(?i)^which\\s+stages\\s+does\\s+(.+?)\\s+go\\s+through[?!.]*$")
    };

    private final Set<String> codes = new LinkedHashSet<>();
    private boolean valid = true;

    public MaterialCodeCandidates(String original) {
        String codeRegion = OUTER_WHITESPACE.matcher(original).replaceAll("");
        if (MISSING_ENGLISH_CODE.matcher(codeRegion).matches()) {
            return;
        }
        if (!HAN.matcher(codeRegion).find()) {
            for (Pattern grammar : ENGLISH_QUESTION) {
                Matcher sentence = grammar.matcher(codeRegion);
                if (sentence.matches()) {
                    codeRegion = sentence.group(1);
                    break;
                }
            }
        }
        Matcher matcher = TOKEN.matcher(codeRegion);
        while (matcher.find()) {
            String token = matcher.group();
            if (!AssistantResponses.validMaterialCode(token)) {
                valid = false;
            } else {
                codes.add(token);
            }
        }
    }

    public boolean isValid() {
        return valid;
    }

    public Set<String> getCodes() {
        return Collections.unmodifiableSet(codes);
    }
}
