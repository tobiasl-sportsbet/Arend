package org.arend.core.expr;

import org.arend.core.expr.type.Type;
import org.arend.core.expr.visitor.ExpressionVisitor;
import org.arend.core.expr.visitor.ExpressionVisitor2;
import org.arend.core.expr.visitor.StripVisitor;
import org.arend.core.sort.Sort;
import org.arend.core.subst.InPlaceLevelSubstVisitor;
import org.arend.core.subst.LevelSubstitution;
import org.arend.core.subst.SubstVisitor;
import org.arend.ext.core.expr.CoreExpressionVisitor;
import org.arend.ext.core.expr.CoreUniverseExpression;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.util.Decision;

import javax.annotation.Nonnull;

public class UniverseExpression extends Expression implements Type, CoreUniverseExpression {
  private Sort mySort;

  public UniverseExpression(Sort sort) {
    assert !sort.isOmega();
    mySort = sort;
  }

  public void substSort(LevelSubstitution substitution) {
    mySort = mySort.subst(substitution);
  }

  @Nonnull
  @Override
  public Sort getSort() {
    return mySort;
  }

  @Override
  public <P, R> R accept(ExpressionVisitor<? super P, ? extends R> visitor, P params) {
    return visitor.visitUniverse(this, params);
  }

  @Override
  public <P1, P2, R> R accept(ExpressionVisitor2<? super P1, ? super P2, ? extends R> visitor, P1 param1, P2 param2) {
    return visitor.visitUniverse(this, param1, param2);
  }

  @Override
  public <P, R> R accept(@Nonnull CoreExpressionVisitor<? super P, ? extends R> visitor, P params) {
    return visitor.visitUniverse(this, params);
  }

  @Override
  public Expression getExpr() {
    return this;
  }

  @Override
  public Sort getSortOfType() {
    return mySort.succ();
  }

  @Override
  public UniverseExpression subst(SubstVisitor substVisitor) {
    return substVisitor.getLevelSubstitution().isEmpty() ? this : new UniverseExpression(mySort.subst(substVisitor.getLevelSubstitution()));
  }

  @Override
  public void subst(InPlaceLevelSubstVisitor substVisitor) {
    substVisitor.visitUniverse(this, null);
  }

  @Override
  public UniverseExpression strip(StripVisitor visitor) {
    return this;
  }

  @Nonnull
  @Override
  public UniverseExpression normalize(@Nonnull NormalizationMode mode) {
    return this;
  }

  @Override
  public Decision isWHNF() {
    return Decision.YES;
  }

  @Override
  public Expression getStuckExpression() {
    return null;
  }
}
