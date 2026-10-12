package com.disgusty.droid_codology.editor;

/** A problem reported for a range of a document (LSP / extension diagnostics). Lines and columns are 0-based. */
public final class Diagnostic {
    public static final int ERROR = 1, WARNING = 2, INFO = 3, HINT = 4;

    public final int line, col, endLine, endCol, severity;
    public final String message, source;

    public Diagnostic(int line, int col, int endLine, int endCol, int severity, String message, String source) {
        this.line = line;
        this.col = col;
        this.endLine = endLine;
        this.endCol = endCol;
        this.severity = severity <= 0 ? ERROR : severity;
        this.message = message;
        this.source = source;
    }

    public int color() {
        switch (severity) {
            case ERROR:
                return 0xFFF14C4C;
            case WARNING:
                return 0xFFCCA700;
            case INFO:
                return 0xFF3794FF;
            default:
                return 0x99A0A0A0;
        }
    }
}
