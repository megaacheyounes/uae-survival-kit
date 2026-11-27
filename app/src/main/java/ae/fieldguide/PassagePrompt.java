package ae.fieldguide;

import java.util.List;

/** The same passage-selection task is used by the app and the Android benchmark. */
public final class PassagePrompt {
    private PassagePrompt() {}

    public static final String SYSTEM = "Match the person's situation to a useful guide step by meaning. The wording may be very different. Pick the step that gives the requested action. For an unrelated or unsupported question choose 0. Return one digit.";

    public static String question(List<String> descriptions, String question) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < descriptions.size(); i++) {
            text.append(i + 1).append(". ").append(descriptions.get(i)).append('\n');
        }
        String safe = question.substring(0, Math.min(320, question.length())).replace('<', ' ').replace('>', ' ');
        return text.append("\nQuestion: ").append(safe).toString();
    }
}
