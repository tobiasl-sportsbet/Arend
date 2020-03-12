package org.arend.typechecking.subexpr;

import org.arend.core.definition.Definition;
import org.arend.core.expr.Expression;
import org.arend.frontend.reference.ConcreteLocatedReferable;
import org.arend.term.concrete.Concrete;
import org.arend.typechecking.TypeCheckingTestCase;
import org.arend.util.Pair;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class CorrespondedSubDefTest extends TypeCheckingTestCase {
  @Test
  public void funTermBody() {
    ConcreteLocatedReferable referable = resolveNamesDef("\\func f => 0");
    Concrete.FunctionDefinition def = (Concrete.FunctionDefinition) referable.getDefinition();
    Concrete.Expression term = def.getBody().getTerm();
    assertNotNull(term);
    Pair<Expression, Concrete.Expression> accept = def.accept(
        new CorrespondedSubDefVisitor(term), typeCheckDef(referable));
    assertNotNull(accept);
    assertEquals("0", accept.proj1.toString());
  }

  @Test
  public void errorReport() {
    ConcreteLocatedReferable referable = resolveNamesDef("\\func f => 0");
    Concrete.FunctionDefinition def = (Concrete.FunctionDefinition) referable.getDefinition();
    Concrete.Expression term = def.getBody().getTerm();
    assertNotNull(term);
    CorrespondedSubDefVisitor visitor = new CorrespondedSubDefVisitor(term);
    def.accept(visitor, typeCheckDef(referable));
    // When matching the telescope of `f`, there's an error
    assertEquals(1, visitor.getExprError().size());
    assertEquals(SubExprError.Kind.Telescope, visitor.getExprError().get(0).getKind());
  }

  @Test
  public void funResultType() {
    ConcreteLocatedReferable referable = resolveNamesDef("\\func f : Nat => 0");
    Concrete.FunctionDefinition def = (Concrete.FunctionDefinition) referable.getDefinition();
    assertNotNull(def.getResultType());
    Pair<Expression, Concrete.Expression> accept = def.accept(
        new CorrespondedSubDefVisitor(def.getResultType()), typeCheckDef(referable));
    assertNotNull(accept);
    assertEquals("Nat", accept.proj1.toString());
  }

  @Test
  public void funParamType() {
    ConcreteLocatedReferable referable = resolveNamesDef("\\func f (a : Nat) => a");
    Concrete.FunctionDefinition def = (Concrete.FunctionDefinition) referable.getDefinition();
    assertFalse(def.getParameters().isEmpty());
    Concrete.Expression type = def.getParameters().get(0).getType();
    assertNotNull(type);
    Pair<Expression, Concrete.Expression> accept = def.accept(
        new CorrespondedSubDefVisitor(type), typeCheckDef(referable));
    assertNotNull(accept);
    assertEquals("Nat", accept.proj1.toString());
  }

  @Test
  public void coelimFun() {
    ConcreteLocatedReferable referable = resolveNamesDef(
        "\\instance t : T\n" +
            "  | A => 114\n" +
            "  | B => 514\n" +
            "  \\where {\n" +
            "    \\class T {\n" +
            "      | A : Nat\n" +
            "      | B : Nat\n" +
            "    }\n" +
            "  }");
    Concrete.FunctionDefinition def = (Concrete.FunctionDefinition) referable.getDefinition();
    Definition coreDef = typeCheckDef(referable);
    Concrete.ClassFieldImpl clause = (Concrete.ClassFieldImpl) def.getBody().getCoClauseElements().get(1);
    Pair<Expression, Concrete.Expression> accept = def.accept(new CorrespondedSubDefVisitor(clause.implementation), coreDef);
    assertNotNull(accept);
    assertEquals("514", accept.proj1.toString());
  }

  @Test
  public void cowithFun() {
    ConcreteLocatedReferable referable = resolveNamesDef(
        "\\func t : R \\cowith\n" +
            "  | pre  => 114\n" +
            "  | post => 514\n" +
            "  \\where {\n" +
            "    \\record R {\n" +
            "      | pre  : Nat\n" +
            "      | post : Nat\n" +
            "    }\n" +
            "  }");
    Concrete.FunctionDefinition def = (Concrete.FunctionDefinition) referable.getDefinition();
    Definition coreDef = typeCheckDef(referable);
    Concrete.ClassFieldImpl clause = (Concrete.ClassFieldImpl) def.getBody().getCoClauseElements().get(1);
    Pair<Expression, Concrete.Expression> accept = def.accept(new CorrespondedSubDefVisitor(clause.implementation), coreDef);
    assertNotNull(accept);
    assertEquals("514", accept.proj1.toString());
  }

  @Test
  public void elimFun() {
    ConcreteLocatedReferable referable = resolveNamesDef(
        "\\func f (a b c : Nat): Nat \\elim b\n" +
            "  | zero => a\n" +
            "  | suc b => c");
    Concrete.FunctionDefinition def = (Concrete.FunctionDefinition) referable.getDefinition();
    Definition coreDef = typeCheckDef(referable);
    List<Concrete.FunctionClause> clauses = def.getBody().getClauses();
    assertFalse(clauses.isEmpty());
    {
      Concrete.Expression expression = clauses.get(0).getExpression();
      assertNotNull(expression);
      Pair<@NotNull Expression, Concrete.Expression> accept = def.accept(
          new CorrespondedSubDefVisitor(expression),
          coreDef);
      assertNotNull(accept);
      assertEquals("a", accept.proj1.toString());
    }
    {
      Concrete.Expression expression = clauses.get(1).getExpression();
      assertNotNull(expression);
      Pair<@NotNull Expression, Concrete.Expression> accept = def.accept(
          new CorrespondedSubDefVisitor(expression),
          coreDef);
      assertNotNull(accept);
      assertEquals("c", accept.proj1.toString());
    }
  }

  @Test
  public void dataParam() {
    ConcreteLocatedReferable referable = resolveNamesDef("\\data D (a : Nat)");
    Concrete.DataDefinition def = (Concrete.DataDefinition) referable.getDefinition();
    assertFalse(def.getParameters().isEmpty());
    Pair<Expression, Concrete.Expression> accept = def.accept(
        new CorrespondedSubDefVisitor(def.getParameters().get(0).getType()), typeCheckDef(referable));
    assertNotNull(accept);
    assertEquals("Nat", accept.proj1.toString());
  }

  @Test
  public void cons() {
    ConcreteLocatedReferable referable = resolveNamesDef("\\data D Int | c Nat");
    Concrete.DataDefinition def = (Concrete.DataDefinition) referable.getDefinition();
    assertFalse(def.getConstructorClauses().isEmpty());
    Pair<Expression, Concrete.Expression> accept = def.accept(
        new CorrespondedSubDefVisitor(def
            .getConstructorClauses().get(0)
            .getConstructors().get(0)
            .getParameters().get(0)
            .getType()
        ), typeCheckDef(referable));
    assertNotNull(accept);
    assertEquals("Nat", accept.proj1.toString());
  }
}