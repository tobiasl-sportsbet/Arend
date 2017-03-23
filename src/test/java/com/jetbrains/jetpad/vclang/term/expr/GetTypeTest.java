package com.jetbrains.jetpad.vclang.term.expr;

import com.jetbrains.jetpad.vclang.core.context.binding.LevelVariable;
import com.jetbrains.jetpad.vclang.core.context.param.DependentLink;
import com.jetbrains.jetpad.vclang.core.context.param.SingleDependentLink;
import com.jetbrains.jetpad.vclang.core.definition.*;
import com.jetbrains.jetpad.vclang.core.expr.ClassCallExpression;
import com.jetbrains.jetpad.vclang.core.expr.Expression;
import com.jetbrains.jetpad.vclang.core.expr.SigmaExpression;
import com.jetbrains.jetpad.vclang.core.expr.visitor.NormalizeVisitor;
import com.jetbrains.jetpad.vclang.core.pattern.elimtree.LeafElimTreeNode;
import com.jetbrains.jetpad.vclang.core.sort.Level;
import com.jetbrains.jetpad.vclang.core.sort.Sort;
import com.jetbrains.jetpad.vclang.term.Prelude;
import com.jetbrains.jetpad.vclang.typechecking.TypeCheckingTestCase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.jetbrains.jetpad.vclang.ExpressionFactory.*;
import static com.jetbrains.jetpad.vclang.core.expr.ExpressionFactory.*;
import static org.junit.Assert.assertEquals;

public class GetTypeTest extends TypeCheckingTestCase {
  private static void testType(Expression expected, TypeCheckClassResult result) {
    assertEquals(expected, ((FunctionDefinition) result.getDefinition("test")).getResultType());
    assertEquals(expected, ((LeafElimTreeNode) ((FunctionDefinition) result.getDefinition("test")).getElimTree()).getExpression().getType());
  }

  @Test
  public void constructorTest() {
    TypeCheckClassResult result = typeCheckClass("\\data List (A : \\1-Type0) | nil | cons A (List A) \\function test => cons 0 nil");
    testType(DataCall((DataDefinition) result.getDefinition("List"), Sort.ZERO, Nat()), result);
  }

  @Test
  public void nilConstructorTest() {
    TypeCheckClassResult result = typeCheckClass("\\data List (A : \\1-Type0) | nil | cons A (List A) \\function test => (List Nat).nil");
    testType(DataCall((DataDefinition) result.getDefinition("List"), Sort.ZERO, Nat()), result);
  }

  @Test
  public void classExtTest() {
    TypeCheckClassResult result = typeCheckClass("\\class Test { \\field A : \\Type0 \\field a : A } \\function test => Test { A => Nat }");
    assertEquals(Universe(new Level(1), new Level(LevelVariable.HVAR, 1)), result.getDefinition("Test").getTypeWithParams(new ArrayList<>(), Sort.STD));
    assertEquals(Universe(Sort.SET0), result.getDefinition("test").getTypeWithParams(new ArrayList<>(), Sort.ZERO));
    testType(Universe(Sort.SET0), result);
  }

  @Test
  public void lambdaTest() {
    TypeCheckClassResult result = typeCheckClass("\\function test => \\lam (f : Nat -> Nat) => f 0");
    testType(Pi(Pi(Nat(), Nat()), Nat()), result);
  }

  @Test
  public void lambdaTest2() {
    TypeCheckClassResult result = typeCheckClass("\\function test => \\lam (A : \\Type0) (x : A) => x");
    SingleDependentLink A = singleParam("A", Universe(new Level(0), new Level(LevelVariable.HVAR)));
    Expression expectedType = Pi(A, Pi(singleParam("x", Ref(A)), Ref(A)));
    testType(expectedType, result);
  }

  @Test
  public void fieldAccTest() {
    TypeCheckClassResult result = typeCheckClass("\\class C { \\field x : Nat \\function f (p : 0 = x) => p } \\function test (p : Nat -> C) => (p 0).f");
    SingleDependentLink p = singleParam("p", Pi(Nat(), new ClassCallExpression((ClassDefinition) result.getDefinition("C"), Sort.ZERO)));
    Expression type = FunCall(Prelude.PATH_INFIX, new Level(0), new Level(1),
        Nat(),
        Zero(),
        FieldCall((ClassField) result.getDefinition("C.x"), Apps(Ref(p), Zero())));
    List<DependentLink> testParams = new ArrayList<>();
    Expression testType = result.getDefinition("test").getTypeWithParams(testParams, Sort.ZERO);
    assertEquals(Pi(p, Pi(type, type)).normalize(NormalizeVisitor.Mode.NF), fromPiParameters(testType, testParams).normalize(NormalizeVisitor.Mode.NF));
  }

  @Test
  public void tupleTest() {
    TypeCheckClassResult result = typeCheckClass("\\function test : \\Sigma (x y : Nat) (x = y) => (0, 0, path (\\lam _ => 0))");
    DependentLink xy = parameter(true, vars("x", "y"), Nat());
    testType(new SigmaExpression(Sort.PROP, params(xy, paramExpr(FunCall(Prelude.PATH_INFIX, new Level(0), new Level(1), Nat(), Ref(xy), Ref(xy.getNext()))))), result);
  }

  @Test
  public void letTest() {
    Definition def = typeCheckDef("\\function test => \\lam (F : Nat -> \\Type0) (f : \\Pi (x : Nat) -> F x) => \\let | x => 0 \\in f x");
    SingleDependentLink F = singleParam("F", Pi(Nat(), Universe(new Level(0), new Level(LevelVariable.HVAR))));
    SingleDependentLink x = singleParam("x", Nat());
    SingleDependentLink f = singleParam("f", Pi(x, Apps(Ref(F), Ref(x))));
    assertEquals(Pi(F, Pi(f, Apps(Ref(F), Zero()))), ((LeafElimTreeNode) ((FunctionDefinition) def).getElimTree()).getExpression().getType().normalize(NormalizeVisitor.Mode.NF));
  }

  @Test
  public void patternConstructor1() {
    TypeCheckClassResult result = typeCheckClass(
        "\\data C (n : Nat) | C (zero) => c1 | C (suc n) => c2 Nat");
    DataDefinition data = (DataDefinition) result.getDefinition("C");
    List<DependentLink> c1Params = new ArrayList<>();
    Expression c1Type = data.getConstructor("c1").getTypeWithParams(c1Params, Sort.ZERO);
    assertEquals(DataCall(data, Sort.ZERO, Zero()), c1Type);
    List<DependentLink> c2Params = new ArrayList<>();
    Expression c2Type = data.getConstructor("c2").getTypeWithParams(c2Params, Sort.ZERO);
    DependentLink params = data.getConstructor("c2").getDataTypeParameters();
    assertEquals(
        fromPiParameters(Pi(Nat(), DataCall(data, Sort.ZERO, Suc(Ref(params)))), DependentLink.Helper.toList(params)),
        fromPiParameters(c2Type, c2Params)
    );
  }

  @Test
  public void patternConstructor2() {
    TypeCheckClassResult result = typeCheckClass(
        "\\data Vec \\Set0 Nat | Vec A zero => Nil | Vec A (suc n) => Cons A (Vec A n)" +
        "\\data D (n : Nat) (Vec Nat n) | D zero _ => dzero | D (suc n) _ => done");
    DataDefinition vec = (DataDefinition) result.getDefinition("Vec");
    DataDefinition d = (DataDefinition) result.getDefinition("D");
    List<DependentLink> dzeroParams = new ArrayList<>();
    Expression dzeroType = d.getConstructor("dzero").getTypeWithParams(dzeroParams, Sort.ZERO);
    assertEquals(
        fromPiParameters(DataCall(d, Sort.ZERO, Zero(), Ref(d.getConstructor("dzero").getDataTypeParameters())), DependentLink.Helper.toList(d.getConstructor("dzero").getDataTypeParameters())),
        fromPiParameters(dzeroType, dzeroParams)
    );
    List<DependentLink> doneAllParams = new ArrayList<>();
    Expression doneType = d.getConstructor("done").getTypeWithParams(doneAllParams, Sort.ZERO);
    DependentLink doneParams = d.getConstructor("done").getDataTypeParameters();
    assertEquals(
        fromPiParameters(DataCall(d, Sort.ZERO, Suc(Ref(doneParams)), Ref(doneParams.getNext())), DependentLink.Helper.toList(d.getConstructor("done").getDataTypeParameters())),
        fromPiParameters(doneType, doneAllParams)
    );
    List<DependentLink> consAllParams = new ArrayList<>();
    Expression consType = vec.getConstructor("Cons").getTypeWithParams(consAllParams, Sort.ZERO);
    DependentLink consParams = vec.getConstructor("Cons").getDataTypeParameters();
    assertEquals(
        fromPiParameters(Pi(Ref(consParams), Pi(DataCall(vec, Sort.ZERO, Ref(consParams), Ref(consParams.getNext())), DataCall(vec, Sort.ZERO, Ref(consParams), Suc(Ref(consParams.getNext()))))), DependentLink.Helper.toList(consParams)),
        fromPiParameters(consType, consAllParams)
    );
  }

  @Test
  public void patternConstructor3() {
    TypeCheckClassResult result = typeCheckClass(
        "\\data D | d \\Type0\n" +
        "\\data C D | C (d A) => c A");
    DataDefinition d = (DataDefinition) result.getDefinition("D");
    DataDefinition c = (DataDefinition) result.getDefinition("C");
    DependentLink A = c.getConstructor("c").getDataTypeParameters();
    List<DependentLink> cParams = new ArrayList<>();
    Expression cType = c.getConstructor("c").getTypeWithParams(cParams, Sort.ZERO);
    assertEquals(
        fromPiParameters(Pi(Ref(A), DataCall(c, Sort.ZERO, ConCall(d.getConstructor("d"), Sort.ZERO, Collections.<Expression>emptyList(), Ref(A)))), DependentLink.Helper.toList(c.getConstructor("c").getDataTypeParameters())),
        fromPiParameters(cType, cParams)
    );
  }

  @Test
  public void patternConstructorDep() {
    TypeCheckClassResult result = typeCheckClass(
        "\\data Box (n : Nat) | box\n" +
        "\\data D (n : Nat) (Box n) | D (zero) _ => d");
    DataDefinition d = (DataDefinition) result.getDefinition("D");
    List<DependentLink> dParams = new ArrayList<>();
    Expression dType = d.getConstructor("d").getTypeWithParams(dParams, Sort.ZERO);
    assertEquals(
        fromPiParameters(DataCall(d, Sort.ZERO, Zero(), Ref(d.getConstructor("d").getDataTypeParameters())), DependentLink.Helper.toList(d.getConstructor("d").getDataTypeParameters())),
        fromPiParameters(dType, dParams)
    );
  }
}
