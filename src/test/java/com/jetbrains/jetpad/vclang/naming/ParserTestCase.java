package com.jetbrains.jetpad.vclang.naming;

import com.jetbrains.jetpad.vclang.VclangTestCase;
import com.jetbrains.jetpad.vclang.frontend.text.Position;
import com.jetbrains.jetpad.vclang.frontend.text.parser.BuildVisitor;
import com.jetbrains.jetpad.vclang.frontend.text.parser.ParserError;
import com.jetbrains.jetpad.vclang.frontend.text.parser.VcgrammarLexer;
import com.jetbrains.jetpad.vclang.frontend.text.parser.VcgrammarParser;
import com.jetbrains.jetpad.vclang.module.ModulePath;
import com.jetbrains.jetpad.vclang.module.source.SourceId;
import com.jetbrains.jetpad.vclang.term.Concrete;
import com.jetbrains.jetpad.vclang.term.expr.ConcreteCompareVisitor;
import org.antlr.v4.runtime.*;

import static org.junit.Assert.assertThat;

public abstract class ParserTestCase extends VclangTestCase {
  private static final SourceId SOURCE_ID = new SourceId() {
    @Override
    public ModulePath getModulePath() {
      return ModulePath.moduleName(toString());
    }
    @Override
    public String toString() {
      return "$TestCase$";
    }
  };

  private VcgrammarParser _parse(String text) {
    ANTLRInputStream input = new ANTLRInputStream(text);
    VcgrammarLexer lexer = new VcgrammarLexer(input);
    lexer.removeErrorListeners();
    lexer.addErrorListener(new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> recognizer, Object o, int line, int pos, String msg, RecognitionException e) {
        errorReporter.report(new ParserError(new Position(SOURCE_ID, line, pos), msg));
      }
    });

    CommonTokenStream tokens = new CommonTokenStream(lexer);
    VcgrammarParser parser = new VcgrammarParser(tokens);
    parser.removeErrorListeners();
    parser.addErrorListener(new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> recognizer, Object o, int line, int pos, String msg, RecognitionException e) {
        errorReporter.report(new ParserError(new Position(SOURCE_ID, line, pos), msg));
      }
    });
    // parser.addErrorListener(new DiagnosticErrorListener());
    // parser.getInterpreter().setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION);
    return parser;
  }


  Concrete.Expression<Position> parseExpr(String text, int errors) {
    VcgrammarParser.ExprContext ctx = _parse(text).expr();
    Concrete.Expression<Position> expr = errorList.isEmpty() ? new BuildVisitor(SOURCE_ID, errorReporter).visitExpr(ctx) : null;
    assertThat(errorList, containsErrors(errors));
    return expr;
  }

  protected Concrete.Expression<Position> parseExpr(String text) {
    return parseExpr(text, 0);
  }

  Concrete.Definition<Position> parseDef(String text, int errors) {
    VcgrammarParser.DefinitionContext ctx = _parse(text).definition();
    Concrete.Definition<Position> definition = errorList.isEmpty() ? new BuildVisitor(SOURCE_ID, errorReporter).visitDefinition(ctx) : null;
    assertThat(errorList, containsErrors(errors));
    return definition;
  }

  protected Concrete.Definition<Position> parseDef(String text) {
    return parseDef(text, 0);
  }

  Concrete.ClassDefinition<Position> parseClass(String name, String text, int errors) {
    VcgrammarParser.StatementsContext tree = _parse(text).statements();
    Concrete.ClassDefinition<Position> classDefinition = errorList.isEmpty() ? new Concrete.ClassDefinition<>(new Position(SOURCE_ID, 0, 0), name, new BuildVisitor(SOURCE_ID, errorReporter).visitStatements(tree)) : null;
    assertThat(errorList, containsErrors(errors));
    // classDefinition.accept(new DefinitionResolveStaticModVisitor(new ConcreteStaticModListener()), null);
    return classDefinition;
  }

  protected Concrete.ClassDefinition<Position> parseClass(String name, String text) {
    return parseClass(name, text, 0);
  }


  protected static boolean compareAbstract(Concrete.Expression<Position> expr1, Concrete.Expression<Position> expr2) {
    return expr1.accept(new ConcreteCompareVisitor(), expr2);
  }
}
