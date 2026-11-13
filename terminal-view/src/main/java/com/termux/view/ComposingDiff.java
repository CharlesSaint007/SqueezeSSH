package com.termux.view;

/**
 * SqueezeSSH addition. With a normal keyboard (suggestions and autocorrect), the keyboard "composes" the word being
 * typed and keeps rewriting it. To show the letters as they are typed, the view mirrors the composing text to the
 * terminal; each rewrite is the backspaces and the new characters needed to turn the old text into the new.
 */
public final class ComposingDiff {
    /** How many characters to erase from the end of the old text. */
    public final int backspaces;
    /** What to type after erasing. */
    public final String insert;

    private ComposingDiff(int backspaces, String insert) {
        this.backspaces = backspaces;
        this.insert = insert;
    }

    public static ComposingDiff between(CharSequence oldText, CharSequence newText) {
        String a = oldText.toString();
        String b = newText.toString();
        // Compare by code point so a surrogate pair is erased and retyped whole.
        int ai = 0, bi = 0;
        while (ai < a.length() && bi < b.length()) {
            int ca = a.codePointAt(ai), cb = b.codePointAt(bi);
            if (ca != cb) break;
            ai += Character.charCount(ca);
            bi += Character.charCount(cb);
        }
        return new ComposingDiff(a.codePointCount(ai, a.length()), b.substring(bi));
    }
}
