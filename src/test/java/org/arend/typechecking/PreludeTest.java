package org.arend.typechecking;

import org.junit.Test;

import static org.arend.Matchers.typeMismatchError;

public class PreludeTest extends TypeCheckingTestCase {
  @Test
  public void testCoe2Sigma() {
    typeCheckModule("\\func foo (A : \\Type) (i j : I) (a : A) : coe2 (\\lam _ => A) i a j = a => idp");
  }

  @Test
  public void testCoe2Left() {
    typeCheckModule("\\func foo (A : I -> \\Type) (j : I) (a : A left) : coe2 A left a j = coe A a j => idp");
  }

  @Test
  public void testCoe2Right() {
    typeCheckModule("\\func foo (A : I -> \\Type) (i : I) (a : A i) : coe2 A i a right = coe2 A i a right => path (\\lam _ => coe (\\lam k => A (I.squeezeR i k)) a right)");
  }

  @Test
  public void testCoe2RightRight() {
    typeCheckModule("\\func foo (A : I -> \\Type) (a : A right) : coe2 A right a right = a => idp");
  }

  @Test
  public void testIsoComparison() {
    typeCheckModule(
      "\\func id (x : Nat) => x\n" +
      "\\func id' (x : Nat) : Nat\n" +
      "  | 0 => 0\n" +
      "  | suc n => suc n\n" +
      "\\func id'-id (x : Nat) : id' x = x\n" +
      "  | 0 => idp\n" +
      "  | suc n => idp\n" +
      "\\func test : path (iso id id (\\lam _ => idp) (\\lam _ => idp)) = path (iso id id' id'-id id'-id) => idp");
  }

  @Test
  public void testIsoComparisonError() {
    typeCheckModule(
      "\\func id (x : Nat) => x\n" +
      "\\func id' (x : Nat) : Nat\n" +
      "  | 0 => 0\n" +
      "  | suc n => suc n\n" +
      "\\func id'-id (x : Nat) : id' x = x\n" +
      "  | 0 => idp\n" +
      "  | suc n => idp\n" +
      "\\func test : path (iso id id (\\lam _ => idp) (\\lam _ => idp)) = path (iso id' id id'-id id'-id) => idp", 1);
    assertThatErrorsAre(typeMismatchError());
  }

  @Test
  public void isoFreeVarEval() {
    typeCheckModule(
      "\\func id (x : Nat) => x\n" +
      "\\func id' (i : I) (x : Nat) : Nat \\elim x\n" +
      "  | 0 => 0\n" +
      "  | suc n => suc n\n" +
      "\\func id'-id (i : I) (x : Nat) : id' i x = x \\elim x\n" +
      "  | 0 => idp\n" +
      "  | suc n => idp\n" +
      "\\func test : coe (\\lam i => iso id (id' i) (id'-id i) (id'-id i) i) 0 right = 0 => idp");
  }

  @Test
  public void isoFreeVarEvalError() {
    typeCheckModule(
      "\\func id (x : Nat) => x\n" +
      "\\func id' (i : I) (x : Nat) : Nat \\elim x\n" +
      "  | 0 => 0\n" +
      "  | suc n => suc n\n" +
      "\\func id'-id (i : I) (x : Nat) : id' i x = x \\elim x\n" +
      "  | 0 => idp\n" +
      "  | suc n => idp\n" +
      "\\func test : coe (\\lam i => iso (id' i) id (id'-id i) (id'-id i) i) 0 right = 0 => idp", 1);
    assertThatErrorsAre(typeMismatchError());
  }
}
