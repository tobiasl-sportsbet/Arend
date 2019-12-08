package org.arend.error;

import org.arend.term.concrete.Concrete;

public class ParsingError extends GeneralError {
  public enum Kind {
    MISPLACED_USE("\\use is allowed only in \\where block of \\data, \\class, or \\func"),
    MISPLACED_COERCE("\\coerce is allowed only in \\where block of \\data or \\class"),
    COERCE_WITHOUT_PARAMETERS("\\coerce must have at least one parameter"),
    LEVEL_IGNORED(Level.WEAK_WARNING, "\\level is ignored"),
    CLASSIFYING_FIELD_IN_RECORD(Level.WEAK_WARNING, "Records cannot have classifying fields"),
    INVALID_PRIORITY("The priority must be between 0 and 10"),
    PRECEDENCE_IGNORED(Level.WEAK_WARNING, "Precedence is ignored"),
    MISPLACED_IMPORT("\\import is allowed only on the top level");

    private final Level level;
    private final String message;

    Kind(Level level, String message) {
      this.level = level;
      this.message = message;
    }

    Kind(String message) {
      this.level = Level.ERROR;
      this.message = message;
    }
  }

  public final Object cause;
  public final Kind kind;

  public ParsingError(Kind kind, Object cause) {
    super(kind.level, kind.message);
    this.kind = kind;
    this.cause = cause;
  }

  public ParsingError(String message, Object cause) {
    super(Level.ERROR, message);
    this.kind = null;
    this.cause = cause;
  }

  @Override
  public Concrete.SourceNode getCauseSourceNode() {
    return cause instanceof Concrete.SourceNode ? (Concrete.SourceNode) cause : null;
  }

  @Override
  public Object getCause() {
    return cause instanceof Concrete.SourceNode ? ((Concrete.SourceNode) cause).getData() : cause;
  }

  @Override
  public Stage getStage() {
    return Stage.PARSER;
  }
}
