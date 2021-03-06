package org.arend.core.expr.visitor;

import org.arend.core.constructor.SingleConstructor;
import org.arend.core.context.binding.Binding;
import org.arend.core.context.binding.inference.InferenceVariable;
import org.arend.core.context.binding.inference.TypeClassInferenceVariable;
import org.arend.core.context.param.*;
import org.arend.core.definition.*;
import org.arend.core.elimtree.*;
import org.arend.core.expr.*;
import org.arend.core.expr.type.Type;
import org.arend.core.expr.type.TypeExpression;
import org.arend.core.pattern.Pattern;
import org.arend.core.sort.Sort;
import org.arend.core.subst.ExprSubstitution;
import org.arend.ext.core.ops.CMP;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.prelude.Prelude;
import org.arend.term.concrete.Concrete;
import org.arend.typechecking.implicitargs.equations.DummyEquations;
import org.arend.typechecking.implicitargs.equations.Equations;
import org.jetbrains.annotations.TestOnly;

import java.util.*;

@SuppressWarnings("BooleanMethodIsAlwaysInverted")
public class CompareVisitor implements ExpressionVisitor2<Expression, Expression, Boolean> {
  private final Map<Binding, Binding> mySubstitution;
  private final Equations myEquations;
  private final Concrete.SourceNode mySourceNode;
  private CMP myCMP;
  private boolean myNormalCompare = true;
  private boolean myOnlySolveVars = false;

  public CompareVisitor(Equations equations, CMP cmp, Concrete.SourceNode sourceNode) {
    mySubstitution = new HashMap<>();
    myEquations = equations;
    mySourceNode = sourceNode;
    myCMP = cmp;
  }

  public static boolean compare(Equations equations, CMP cmp, Expression expr1, Expression expr2, Expression type, Concrete.SourceNode sourceNode) {
    return new CompareVisitor(equations, cmp, sourceNode).compare(expr1, expr2, type);
  }

  public boolean compare(ElimTree elimTree1, ElimTree elimTree2) {
    if (elimTree1.getSkip() != elimTree2.getSkip()) {
      return false;
    }

    if (elimTree1 instanceof LeafElimTree && elimTree2 instanceof LeafElimTree) {
      return ((LeafElimTree) elimTree1).getClauseIndex() == ((LeafElimTree) elimTree2).getClauseIndex() && Objects.equals(((LeafElimTree) elimTree1).getArgumentIndices(), ((LeafElimTree) elimTree2).getArgumentIndices());
    } else if (elimTree1 instanceof BranchElimTree && elimTree2 instanceof BranchElimTree) {
      BranchElimTree branchElimTree1 = (BranchElimTree) elimTree1;
      BranchElimTree branchElimTree2 = (BranchElimTree) elimTree2;
      if (branchElimTree1.keepConCall() != branchElimTree2.keepConCall() || branchElimTree1.getChildren().size() != branchElimTree2.getChildren().size()) {
        return false;
      }
      SingleConstructor single1 = branchElimTree1.getSingleConstructorKey();
      if (single1 != null) {
        SingleConstructor single2 = branchElimTree2.getSingleConstructorKey();
        return single2 != null && single1.compare(single2, myEquations, mySourceNode) && compare(branchElimTree1.getSingleConstructorChild(), branchElimTree2.getSingleConstructorChild());
      } else {
        for (Map.Entry<BranchKey, ElimTree> entry : branchElimTree1.getChildren()) {
          ElimTree subTree = branchElimTree2.getChild(entry.getKey());
          if (subTree == null || !compare(entry.getValue(), subTree)) {
            return false;
          }
        }
      }
      return true;
    } else {
      return false;
    }
  }

  @TestOnly
  public static boolean compare(Equations equations, ElimBody elimBody1, ElimBody elimBody2, Concrete.SourceNode sourceNode) {
    return new CompareVisitor(equations, CMP.EQ, sourceNode).compare(elimBody1, elimBody2, null);
  }

  private Boolean compare(ElimBody elimBody1, ElimBody elimBody2, Expression type) {
    if (elimBody1 == elimBody2) {
      return true;
    }
    if (elimBody1.getClauses().size() != elimBody2.getClauses().size()) {
      return false;
    }
    for (int i = 0; i < elimBody1.getClauses().size(); i++) {
      ElimClause<Pattern> clause1 = elimBody1.getClauses().get(i);
      ElimClause<Pattern> clause2 = elimBody2.getClauses().get(i);
      if (!compareParameters(clause1.getParameters(), clause2.getParameters())) {
        return false;
      }
      boolean ok = clause1.getExpression() == null && clause2.getExpression() == null || clause1.getExpression() != null && clause2.getExpression() != null && compare(clause1.getExpression(), clause2.getExpression(), type);
      for (DependentLink link = clause2.getParameters(); link.hasNext(); link = link.getNext()) {
        mySubstitution.remove(link);
      }
      if (!ok) {
        return false;
      }
    }

    return true;
  }

  public boolean nonNormalizingCompare(Expression expr1, Expression expr2, Expression type) {
    expr1 = expr1.getUnderlyingExpression();
    expr2 = expr2.getUnderlyingExpression();

    // Optimization for let clause calls
    if (expr1 instanceof ReferenceExpression && expr2 instanceof ReferenceExpression && ((ReferenceExpression) expr1).getBinding() == ((ReferenceExpression) expr2).getBinding()) {
      return true;
    }

    // Another optimization
    boolean check;
    if (expr1 instanceof FunCallExpression) {
      check = expr2 instanceof FunCallExpression && ((FunCallExpression) expr1).getDefinition() == ((FunCallExpression) expr2).getDefinition() && !((FunCallExpression) expr1).getDefinition().isSFunc();
    } else if (expr1 instanceof AppExpression) {
      check = expr2 instanceof AppExpression;
    } else if (expr1 instanceof FieldCallExpression) {
      check = expr2 instanceof FieldCallExpression && ((FieldCallExpression) expr1).getDefinition() == ((FieldCallExpression) expr2).getDefinition() && !((FieldCallExpression) expr1).getDefinition().isProperty();
    } else if (expr1 instanceof ProjExpression) {
      check = expr2 instanceof ProjExpression && ((ProjExpression) expr1).getField() == ((ProjExpression) expr2).getField();
    } else {
      check = false;
    }

    if (check) {
      CMP origCMP = myCMP;
      myCMP = CMP.EQ;
      boolean normalCompare = myNormalCompare;
      myNormalCompare = false;

      boolean ok = expr1.accept(this, expr2, type);

      myNormalCompare = normalCompare;
      myCMP = origCMP;
      return ok;
    }

    return false;
  }

  public boolean normalizedCompare(Expression expr1, Expression expr2, Expression type) {
    Expression stuck1 = expr1.getStuckExpression();
    Expression stuck2 = expr2.getStuckExpression();
    if (stuck1 != null && stuck1.isError() && (stuck2 == null || !stuck2.isInstance(InferenceReferenceExpression.class)) ||
      stuck2 != null && stuck2.isError() && (stuck1 == null || !stuck1.isInstance(InferenceReferenceExpression.class))) {
      return true;
    }

    InferenceVariable stuckVar1 = expr1.getInferenceVariable();
    InferenceVariable stuckVar2 = expr2.getInferenceVariable();
    if (stuckVar1 != null || stuckVar2 != null) {
      return myNormalCompare && myEquations.addEquation(expr1, expr2.subst(getSubstitution()), type, myCMP, stuckVar1 != null ? stuckVar1.getSourceNode() : stuckVar2.getSourceNode(), stuckVar1, stuckVar2);
    }

    boolean onlySolveVars = myOnlySolveVars;
    if (myNormalCompare && !myOnlySolveVars) {
      Expression normType = type == null ? null : type.getUnderlyingExpression();
      boolean allowProp = normType instanceof DataCallExpression && ((DataCallExpression) normType).getDefinition().getConstructors().isEmpty() || !expr1.canBeConstructor() && !expr2.canBeConstructor();
      if (normType instanceof SigmaExpression && !((SigmaExpression) normType).getParameters().hasNext() ||
          normType instanceof ClassCallExpression && ((ClassCallExpression) normType).getNumberOfNotImplementedFields() == 0 ||
          allowProp && normType != null && Sort.PROP.equals(normType.getSortOfType())) {
        myOnlySolveVars = true;
      }

      if (!myOnlySolveVars && (normType == null || normType.getStuckInferenceVariable() != null || normType instanceof ClassCallExpression)) {
        Expression type1 = expr1.getType();
        if (type1 != null && type1.getStuckInferenceVariable() != null) {
          type1 = null;
        }
        if (type1 != null) {
          type1 = type1.normalize(NormalizationMode.WHNF);
          if (allowProp) {
            Sort sort1 = type1.getSortOfType();
            if (sort1 != null && sort1.isProp() && !type1.isInstance(ClassCallExpression.class)) {
              myOnlySolveVars = true;
            }
          }
        }

        if (!myOnlySolveVars) {
          Expression type2 = expr2.getType();
          if (type2 != null && type2.getStuckInferenceVariable() != null) {
            type2 = null;
          }
          if (type2 != null) {
            type2 = type2.normalize(NormalizationMode.WHNF);
            if (allowProp) {
              Sort sort2 = type2.getSortOfType();
              if (sort2 != null && sort2.isProp() && !type2.isInstance(ClassCallExpression.class)) {
                myOnlySolveVars = true;
              }
            }
          }

          if (!myOnlySolveVars && type1 != null && type2 != null) {
            ClassCallExpression classCall1 = type1.cast(ClassCallExpression.class);
            ClassCallExpression classCall2 = type2.cast(ClassCallExpression.class);
            if (classCall1 != null && classCall2 != null && compareClassInstances(expr1, classCall1, expr2, classCall2, normType)) {
              return true;
            }
          }
        }
      }
    }

    CMP origCMP = myCMP;
    if (!myOnlySolveVars) {
      Boolean dataAndApp = checkDefCallAndApp(expr1, expr2, true);
      if (dataAndApp != null) {
        return dataAndApp;
      }
      dataAndApp = checkDefCallAndApp(expr2, expr1, false);
      if (dataAndApp != null) {
        return dataAndApp;
      }
    }

    Expression uExpr1 = expr1.getUnderlyingExpression();
    if (uExpr1 instanceof ErrorExpression) {
      return true;
    }
    if (!(uExpr1 instanceof UniverseExpression || uExpr1 instanceof PiExpression || uExpr1 instanceof ClassCallExpression || uExpr1 instanceof DataCallExpression || uExpr1 instanceof AppExpression || uExpr1 instanceof SigmaExpression || uExpr1 instanceof LamExpression)) {
      myCMP = CMP.EQ;
    }

    boolean ok;
    Expression uExpr2 = expr2.getUnderlyingExpression();
    if (uExpr2 instanceof ErrorExpression) {
      return true;
    }
    if (uExpr2 instanceof ConCallExpression && ((ConCallExpression) uExpr2).getDefinition() == Prelude.PATH_CON) {
      ok = visitDefCall((ConCallExpression) uExpr2, expr1, type, false);
    } else if (uExpr2 instanceof LamExpression) {
      ok = visitLam((LamExpression) uExpr2, expr1, type, false);
    } else if (uExpr2 instanceof TupleExpression) {
      ok = visitTuple((TupleExpression) uExpr2, expr1, false);
    } else {
      ok = expr1.accept(this, expr2, type);
    }

    if (!ok && !myOnlySolveVars) {
      InferenceVariable variable1 = stuck1 == null ? null : stuck1.getInferenceVariable();
      InferenceVariable variable2 = stuck2 == null ? null : stuck2.getInferenceVariable();
      ok = (variable1 != null || variable2 != null) && myNormalCompare && myEquations.addEquation(expr1, expr2.subst(getSubstitution()), type, origCMP, variable1 != null ? variable1.getSourceNode() : variable2.getSourceNode(), variable1, variable2);
    }
    if (myOnlySolveVars) {
      ok = true;
    }
    myOnlySolveVars = onlySolveVars;
    return ok;
  }

  public Boolean compare(Expression expr1, Expression expr2, Expression type) {
    expr1 = expr1.getUnderlyingExpression();
    expr2 = expr2.getUnderlyingExpression();
    if (expr1 == expr2) {
      return true;
    }

    InferenceReferenceExpression infRefExpr1 = expr1.cast(InferenceReferenceExpression.class);
    InferenceReferenceExpression infRefExpr2 = expr2.cast(InferenceReferenceExpression.class);
    if (infRefExpr1 != null && infRefExpr2 != null && infRefExpr1.getVariable() == infRefExpr2.getVariable()) {
      return true;
    }
    if (infRefExpr1 != null && infRefExpr1.getVariable() instanceof InferenceVariable) {
      return myNormalCompare && myEquations.addEquation(infRefExpr1, expr2.subst(getSubstitution()).normalize(NormalizationMode.WHNF), type, myCMP, ((InferenceVariable) infRefExpr1.getVariable()).getSourceNode(), (InferenceVariable) infRefExpr1.getVariable(), expr2.getStuckInferenceVariable());
    }
    if (infRefExpr2 != null && infRefExpr2.getVariable() instanceof InferenceVariable) {
      return myNormalCompare && myEquations.addEquation(expr1.normalize(NormalizationMode.WHNF), infRefExpr2, type, myCMP, ((InferenceVariable) infRefExpr2.getVariable()).getSourceNode(), expr1.getStuckInferenceVariable(), (InferenceVariable) infRefExpr2.getVariable());
    }

    InferenceVariable stuckVar1 = expr1.getStuckInferenceVariable();
    InferenceVariable stuckVar2 = expr2.getStuckInferenceVariable();
    if (stuckVar1 != stuckVar2 && (!myNormalCompare || myEquations == DummyEquations.getInstance())) {
      return myOnlySolveVars;
    }
    if (stuckVar1 == stuckVar2 && nonNormalizingCompare(expr1, expr2, type)) {
      return true;
    }

    if (myOnlySolveVars && (stuckVar1 != null || stuckVar2 != null)) {
      return true;
    }

    return myNormalCompare && normalizedCompare(expr1.normalize(NormalizationMode.WHNF), expr2.normalize(NormalizationMode.WHNF), type == null ? null : type.normalize(NormalizationMode.WHNF));
  }

  private ExprSubstitution getSubstitution() {
    ExprSubstitution substitution = new ExprSubstitution();
    for (Map.Entry<Binding, Binding> entry : mySubstitution.entrySet()) {
      substitution.add(entry.getKey(), new ReferenceExpression(entry.getValue()));
    }
    return substitution;
  }

  @Override
  public Boolean visitApp(AppExpression expr1, Expression expr2, Expression type) {
    List<Expression> args1 = new ArrayList<>();
    List<Expression> args2 = new ArrayList<>();
    Expression fun1 = expr1.getArguments(args1);
    Expression fun2 = expr2.getArguments(args2);

    InferenceVariable var1 = fun1.getInferenceVariable();
    InferenceVariable var2 = fun2.getInferenceVariable();
    if (var1 != null || var2 != null) {
      if (myNormalCompare && !myOnlySolveVars && myEquations.addEquation(expr1, expr2.subst(getSubstitution()), type, myCMP, var1 != null ? var1.getSourceNode() : var2.getSourceNode(), var1, var2)) {
        return true;
      }
    }

    if (args1.size() != args2.size()) {
      return false;
    }

    if (!compare(fun1, fun2, null)) {
      return false;
    }
    if (args1.isEmpty()) {
      return true;
    }

    myCMP = CMP.EQ;
    Expression type1 = fun1.getType();
    List<SingleDependentLink> params = Collections.emptyList();
    if (type1 != null) {
      params = new ArrayList<>();
      type1.getPiParameters(params, false);
    }

    for (int i = 0; i < args1.size(); i++) {
      if (!compare(args1.get(i), args2.get(i), i < params.size() ? params.get(i).getTypeExpr() : null)) {
        return false;
      }
    }

    return true;
  }

  private Boolean comparePathEta(ConCallExpression conCall1, Expression expr2, boolean correctOrder) {
    SingleDependentLink param = new TypedSingleDependentLink(true, "i", ExpressionFactory.Interval());
    ReferenceExpression paramRef = new ReferenceExpression(param);
    List<Expression> args = new ArrayList<>(5);
    for (Expression arg : conCall1.getDataTypeArguments()) {
      args.add(correctOrder ? arg : arg.subst(getSubstitution()));
    }
    args.add(expr2);
    args.add(paramRef);
    expr2 = new LamExpression(conCall1.getSortArgument(), param, new FunCallExpression(Prelude.AT, conCall1.getSortArgument(), args));
    Expression type = new PiExpression(conCall1.getSortArgument(), param, AppExpression.make(conCall1.getDataTypeArguments().get(0), paramRef, true));
    return correctOrder ? compare(conCall1.getDefCallArguments().get(0), expr2, type) : compare(expr2, conCall1.getDefCallArguments().get(0), type);
  }

  private boolean compareDef(DefCallExpression expr1, DefCallExpression expr2, boolean correctOrder) {
    if (expr2 == null || expr1.getDefinition() != expr2.getDefinition()) {
      return false;
    }
    UniverseKind universeKind = expr1.getUniverseKind();
    if (universeKind == UniverseKind.NO_UNIVERSES) {
      return true;
    }
    CMP cmp = universeKind == UniverseKind.ONLY_COVARIANT ? myCMP : CMP.EQ;
    return correctOrder
      ? Sort.compare(expr1.getSortArgument(), expr2.getSortArgument(), cmp, myNormalCompare ? myEquations : DummyEquations.getInstance(), mySourceNode)
      : Sort.compare(expr2.getSortArgument(), expr1.getSortArgument(), cmp, myNormalCompare ? myEquations : DummyEquations.getInstance(), mySourceNode);
  }

  private Boolean visitDefCall(DefCallExpression expr1, Expression expr2, Expression type, boolean correctOrder) {
    DefCallExpression defCall2 = expr2.cast(DefCallExpression.class);
    if (expr1.getDefinition() == Prelude.PATH_CON && !(defCall2 instanceof ConCallExpression)) {
      return comparePathEta((ConCallExpression) expr1, expr2, correctOrder);
    }

    if (!compareDef(expr1, defCall2, correctOrder)) {
      return false;
    }

    ExprSubstitution substitution;
    if (expr1 instanceof ConCallExpression) {
      substitution = null;
      DataCallExpression dataCall = type == null ? null : type.cast(DataCallExpression.class);
      if (dataCall != null) {
        substitution = new ExprSubstitution();
        dataCall.addArguments(substitution);
        }
    } else {
      substitution = new ExprSubstitution();
    }

    if (expr1.getDefinition() == Prelude.ISO) {
      return correctOrder ? compareIsoArgs(expr1.getDefCallArguments(), defCall2.getDefCallArguments(), substitution) : compareIsoArgs(defCall2.getDefCallArguments(), expr1.getDefCallArguments(), substitution);
    }
    return correctOrder ? compareLists(expr1.getDefCallArguments(), defCall2.getDefCallArguments(), expr1.getDefinition().getParameters(), expr1.getDefinition(), substitution) : compareLists(defCall2.getDefCallArguments(), expr1.getDefCallArguments(), defCall2.getDefinition().getParameters(), defCall2.getDefinition(), substitution);
  }

  public boolean compareIsoArgs(List<? extends Expression> list1, List<? extends Expression> list2, ExprSubstitution substitution) {
    DependentLink link = Prelude.ISO.getParameters();
    if (!compare(list1.get(0), list2.get(0), link.getTypeExpr())) {
      return false;
    }
    substitution.add(link, (myCMP == CMP.LE ? list2 : list1).get(0));
    link = link.getNext();

    if (!compare(list1.get(1), list2.get(1), link.getTypeExpr())) {
      return false;
    }
    substitution.add(link, (myCMP == CMP.LE ? list2 : list1).get(1));
    link = link.getNext();

    if (!compare(list1.get(2), list2.get(2), link.getTypeExpr().subst(substitution))) {
      return false;
    }
    return compare(list1.get(6), list2.get(6), link.getNext().getNext().getNext().getNext().getTypeExpr());
  }

  private boolean visitDefCall(DefCallExpression expr1, Expression expr2, Expression type) {
    if (expr1 instanceof ConCallExpression) {
      IntegerExpression intExpr = expr2.cast(IntegerExpression.class);
      if (intExpr != null) {
        return visitInteger(intExpr, expr1);
      }
    }
    return visitDefCall(expr1, expr2, type, true);
  }

  @Override
  public Boolean visitFunCall(FunCallExpression expr1, Expression expr2, Expression type) {
    return visitDefCall(expr1, expr2, type);
  }

  @Override
  public Boolean visitConCall(ConCallExpression expr1, Expression expr2, Expression type) {
    return visitDefCall(expr1, expr2, type);
  }

  @Override
  public Boolean visitDataCall(DataCallExpression expr1, Expression expr2, Expression type) {
    return visitDefCall(expr1, expr2, type);
  }

  private Boolean checkDefCallAndApp(Expression expr1, Expression expr2, boolean correctOrder) {
    DataCallExpression dataCall1 = expr1.cast(DataCallExpression.class);
    ClassCallExpression classCall1 = dataCall1 == null ? expr1.cast(ClassCallExpression.class) : null;
    if (dataCall1 == null && classCall1 == null) {
      return null;
    }
    AppExpression app2 = expr2.cast(AppExpression.class);
    if (app2 == null) {
      return null;
    }

    List<Expression> args = new ArrayList<>();
    while (true) {
      args.add(app2.getArgument());
      Expression fun = app2.getFunction();
      app2 = fun.cast(AppExpression.class);
      if (app2 != null) {
        continue;
      }

      TypeClassInferenceVariable variable;
      FieldCallExpression fieldCall = fun.cast(FieldCallExpression.class);
      if (fieldCall != null) {
        InferenceVariable infVar = fieldCall.getArgument().getInferenceVariable();
        variable = infVar instanceof TypeClassInferenceVariable ? (TypeClassInferenceVariable) infVar : null;
      } else {
        variable = null;
      }
      if (variable == null || dataCall1 != null && args.size() > dataCall1.getDefCallArguments().size() || classCall1 != null && args.size() > classCall1.getDefinition().getNumberOfNotImplementedFields()) {
        return null;
      }
      if (myOnlySolveVars && !variable.isSolved()) {
        return false;
      }
      Collections.reverse(args);

      DependentLink dataParams;
      List<Expression> oldDataArgs;
      if (dataCall1 != null) {
        dataParams = dataCall1.getDefinition().getParameters();
        oldDataArgs = dataCall1.getDefCallArguments();
      } else {
        oldDataArgs = new ArrayList<>();
        for (ClassField field : classCall1.getDefinition().getFields()) {
          if (!field.getReferable().isParameterField()) {
            break;
          }
          Expression implementation = classCall1.getAbsImplementationHere(field);
          if (implementation != null) {
            oldDataArgs.add(implementation);
          } else {
            if (!classCall1.getDefinition().isImplemented(field)) {
              break;
            }
          }
        }
        if (args.size() > oldDataArgs.size() || classCall1.getImplementedHere().size() > oldDataArgs.size() && !(correctOrder && myCMP == CMP.LE || !correctOrder && myCMP == CMP.GE)) {
          return null;
        }
        dataParams = classCall1.getClassFieldParameters();
      }

      List<Expression> oldList = oldDataArgs.subList(oldDataArgs.size() - args.size(), oldDataArgs.size());
      if (!compareLists(correctOrder ? oldList : args, correctOrder ? args : oldList, dataParams, dataCall1 == null ? null : dataCall1.getDefinition(), new ExprSubstitution())) {
        return false;
      }

      Expression lam;
      Sort codSort;
      List<SingleDependentLink> params = new ArrayList<>();
      if (dataCall1 != null) {
        int numberOfOldArgs = oldDataArgs.size() - args.size();
        for (int i = 0; i < numberOfOldArgs; i++) {
          dataParams = dataParams.getNext();
        }
        List<Expression> newDataArgs = new ArrayList<>(oldDataArgs.subList(0, numberOfOldArgs));
        lam = new DataCallExpression(dataCall1.getDefinition(), dataCall1.getSortArgument(), newDataArgs);
        codSort = dataCall1.getDefinition().getSort();

        SingleDependentLink firstParam = null;
        SingleDependentLink lastParam = null;
        for (; dataParams.hasNext(); dataParams = dataParams.getNext()) {
          SingleDependentLink link;
          if (dataParams instanceof TypedDependentLink) {
            link = new TypedSingleDependentLink(dataParams.isExplicit(), dataParams.getName(), dataParams.getType());
          } else {
            link = new UntypedSingleDependentLink(dataParams.getName());
          }
          newDataArgs.add(new ReferenceExpression(link));
          if (firstParam == null) {
            firstParam = link;
          }
          if (lastParam == null) {
            lastParam = link;
          } else {
            lastParam.setNext(link);
          }
          if (link instanceof TypedSingleDependentLink) {
            params.add(firstParam);
            firstParam = null;
            lastParam = null;
          }
        }
      } else {
        Map<ClassField, Expression> implementations = new HashMap<>();
        codSort = classCall1.getSort();
        ClassCallExpression classCall = new ClassCallExpression(classCall1.getDefinition(), classCall1.getSortArgument(), implementations, codSort, classCall1.getUniverseKind());
        int i = 0;
        for (ClassField field : classCall1.getDefinition().getFields()) {
          if (!classCall1.getDefinition().isImplemented(field)) {
            if (i < oldDataArgs.size() - args.size()) {
              implementations.put(field, classCall1.getImplementationHere(field, new ReferenceExpression(classCall.getThisBinding())));
              i++;
            } else {
              PiExpression piType = field.getType(classCall1.getSortArgument());
              Expression type = piType.getCodomain();
              TypedSingleDependentLink link = new TypedSingleDependentLink(field.getReferable().isExplicitField(), field.getName(), type instanceof Type ? (Type) type : new TypeExpression(type, piType.getResultSort()));
              params.add(link);
              implementations.put(field, new ReferenceExpression(link));
            }
            if (implementations.size() == oldDataArgs.size()) {
              break;
            }
          }
        }
        classCall.updateHasUniverses();
        lam = classCall;
      }

      for (int i = params.size() - 1; i >= 0; i--) {
        if (!myNormalCompare || myOnlySolveVars || !myEquations.supportsLevels()) {
          return false;
        }
        codSort = PiExpression.generateUpperBound(params.get(i).getType().getSortOfType(), codSort, myEquations, mySourceNode);
        lam = new LamExpression(codSort, params.get(i), lam);
      }

      Expression finalExpr1 = correctOrder ? lam : fun;
      Expression finalExpr2 = correctOrder ? fun : lam.subst(getSubstitution());
      if (variable.isSolved()) {
        CompareVisitor visitor = new CompareVisitor(myEquations, myCMP, variable.getSourceNode());
        visitor.myNormalCompare = myNormalCompare;
        visitor.myOnlySolveVars = myOnlySolveVars;
        return visitor.compare(finalExpr1, finalExpr2, null);
      } else {
        return myNormalCompare && myEquations.addEquation(finalExpr1, finalExpr2, null, myCMP, variable.getSourceNode(), correctOrder ? null : variable, correctOrder ? variable : null) ? true : null;
      }
    }
  }

  @Override
  public Boolean visitFieldCall(FieldCallExpression fieldCall1, Expression expr2, Expression type) {
    FieldCallExpression fieldCall2 = expr2.cast(FieldCallExpression.class);
    if (fieldCall2 == null || fieldCall1.getDefinition() != fieldCall2.getDefinition()) {
      return false;
    }

    return compare(fieldCall1.getArgument(), fieldCall2.getArgument(), null);
  }

  private boolean checkSubclassImpl(ClassCallExpression classCall1, ClassCallExpression classCall2, boolean correctOrder) {
    CMP origCMP = myCMP;
    for (Map.Entry<ClassField, Expression> entry : classCall2.getImplementedHere().entrySet()) {
      if (entry.getKey().isProperty()) {
        continue;
      }

      Expression impl1 = classCall1.getAbsImplementationHere(entry.getKey());
      Binding binding = classCall1.getThisBinding();
      if (impl1 == null) {
        AbsExpression absImpl1 = classCall1.getDefinition().getImplementation(entry.getKey());
        if (absImpl1 != null) {
          impl1 = absImpl1.getExpression();
          binding = absImpl1.getBinding();
        }
      }
      if (impl1 == null) {
        return false;
      }
      if (!entry.getKey().isCovariant()) {
        myCMP = CMP.EQ;
      }
      mySubstitution.put(classCall2.getThisBinding(), binding);
      boolean ok = compare(correctOrder ? impl1 : entry.getValue(), correctOrder ? entry.getValue() : impl1, entry.getKey().getType(classCall2.getSortArgument()).applyExpression(new ReferenceExpression(binding)));
      mySubstitution.remove(classCall2.getThisBinding());
      if (!ok) {
        return false;
      }
      myCMP = origCMP;
    }
    return true;
  }

  private boolean checkClassCallSortArguments(ClassCallExpression classCall1, ClassCallExpression classCall2, CMP onSuccess, CMP onFailure) {
    ReferenceExpression thisExpr = new ReferenceExpression(classCall1.getThisBinding());
    boolean ok = true;
    for (Map.Entry<ClassField, AbsExpression> entry : classCall1.getDefinition().getImplemented()) {
      if (entry.getKey().getUniverseKind() != UniverseKind.NO_UNIVERSES && classCall2.getDefinition().getFields().contains(entry.getKey()) && !classCall2.isImplemented(entry.getKey())) {
        Expression type = entry.getValue().apply(thisExpr).getType();
        if (type == null) {
          ok = false;
          break;
        }
        CMP origCmp = myCMP;
        myCMP = CMP.LE;
        ok = compare(type, entry.getKey().getType(classCall2.getSortArgument()).applyExpression(thisExpr), Type.OMEGA);
        myCMP = origCmp;
        if (!ok) {
          return false;
        }
      }
    }
    if (ok) {
      for (Map.Entry<ClassField, Expression> entry : classCall1.getImplementedHere().entrySet()) {
        if (entry.getKey().getUniverseKind() != UniverseKind.NO_UNIVERSES && classCall2.getDefinition().getFields().contains(entry.getKey()) && !classCall2.isImplemented(entry.getKey())) {
          Expression type = entry.getValue().getType();
          if (type == null) {
            ok = false;
            break;
          }
          CMP origCmp = myCMP;
          myCMP = CMP.LE;
          ok = compare(type, entry.getKey().getType(classCall2.getSortArgument()).applyExpression(thisExpr), Type.OMEGA);
          myCMP = origCmp;
          if (!ok) {
            return false;
          }
        }
      }
    }

    if (ok) {
      return onSuccess == null || myNormalCompare && Sort.compare(classCall1.getSortArgument(), classCall2.getSortArgument(), onSuccess, myEquations, mySourceNode);
    } else {
      return myNormalCompare && Sort.compare(classCall1.getSortArgument(), classCall2.getSortArgument(), onFailure, myEquations, mySourceNode);
    }
  }

  public boolean compareClassCallSortArguments(ClassCallExpression classCall1, ClassCallExpression classCall2) {
    UniverseKind kind1 = classCall1.getUniverseKind();
    UniverseKind kind2 = classCall2.getUniverseKind();
    if (kind1 == UniverseKind.NO_UNIVERSES && kind2 == UniverseKind.NO_UNIVERSES) {
      return true;
    }
    if (myCMP == CMP.EQ || kind1 == kind2) {
      return Sort.compare(classCall1.getSortArgument(), classCall2.getSortArgument(), kind1 == UniverseKind.ONLY_COVARIANT ? myCMP : CMP.EQ, myNormalCompare ? myEquations : DummyEquations.getInstance(), mySourceNode);
    }
    if (!Sort.compare(classCall1.getSortArgument(), classCall2.getSortArgument(), myCMP, DummyEquations.getInstance(), mySourceNode)) {
      CMP onSuccess = kind1 == UniverseKind.NO_UNIVERSES || kind2 == UniverseKind.NO_UNIVERSES ? null : CMP.LE;
      CMP onFailure = kind1 == UniverseKind.WITH_UNIVERSES || kind2 == UniverseKind.WITH_UNIVERSES ? CMP.EQ : CMP.LE;
      return myCMP == CMP.LE ? checkClassCallSortArguments(classCall1, classCall2, onSuccess, onFailure) : checkClassCallSortArguments(classCall2, classCall1, onSuccess, onFailure);
    }
    return true;
  }

  @Override
  public Boolean visitClassCall(ClassCallExpression expr1, Expression expr2, Expression type) {
    ClassCallExpression classCall2 = expr2.cast(ClassCallExpression.class);
    if (classCall2 == null) {
      return false;
    }

    if (!compareClassCallSortArguments(expr1, classCall2)) {
      return false;
    }

    if (myCMP == CMP.LE) {
      if (!expr1.getDefinition().isSubClassOf(classCall2.getDefinition())) {
        return false;
      }
      return checkSubclassImpl(expr1, classCall2, true);
    }

    if (myCMP == CMP.GE) {
      if (!classCall2.getDefinition().isSubClassOf(expr1.getDefinition())) {
        return false;
      }
      return checkSubclassImpl(classCall2, expr1, false);
    }

    return expr1.getDefinition() == classCall2.getDefinition() && expr1.getImplementedHere().size() == classCall2.getImplementedHere().size() && checkSubclassImpl(expr1, classCall2, true);
  }

  @Override
  public Boolean visitReference(ReferenceExpression expr1, Expression expr2, Expression type) {
    ReferenceExpression ref2 = expr2.cast(ReferenceExpression.class);
    if (ref2 == null) {
      return false;
    }

    Binding binding2 = ref2.getBinding();
    Binding subst2 = mySubstitution.get(binding2);
    if (subst2 != null) {
      binding2 = subst2;
    }
    return binding2 == expr1.getBinding();
  }

  @Override
  public Boolean visitInferenceReference(InferenceReferenceExpression expr1, Expression expr2, Expression type) {
    if (expr1.getSubstExpression() == null) {
      InferenceReferenceExpression infRefExpr2 = expr2.cast(InferenceReferenceExpression.class);
      return infRefExpr2 != null && infRefExpr2.getVariable() == expr1.getVariable();
    } else {
      return expr1.getSubstExpression().accept(this, expr2, type);
    }
  }

  @Override
  public Boolean visitSubst(SubstExpression expr, Expression expr2, Expression type) {
    return expr.getSubstExpression().accept(this, expr2, type);
  }

  private Boolean visitLam(LamExpression expr1, Expression expr2, Expression type, boolean correctOrder) {
    List<DependentLink> params1 = new ArrayList<>();
    List<DependentLink> params2 = new ArrayList<>();
    Expression body1 = expr1.getLamParameters(params1);
    Expression body2 = expr2.getLamParameters(params2);

    for (int i = 0; i < params1.size() && i < params2.size(); i++) {
      mySubstitution.put(correctOrder ? params2.get(i) : params1.get(i), correctOrder ? params1.get(i) : params2.get(i));
    }

    if (params1.size() < params2.size()) {
      for (int i = params1.size(); i < params2.size(); i++) {
        body1 = AppExpression.make(body1, new ReferenceExpression(params2.get(i)), params2.get(i).isExplicit());
      }
    }
    if (params2.size() < params1.size()) {
      for (int i = params2.size(); i < params1.size(); i++) {
        body2 = AppExpression.make(body2, new ReferenceExpression(params1.get(i)), params1.get(i).isExplicit());
      }
    }

    type = type == null ? null : type.dropPiParameter(Math.max(params1.size(), params2.size()));
    Boolean result = compare(correctOrder ? body1 : body2, correctOrder ? body2 : body1, type);
    for (int i = 0; i < params1.size() && i < params2.size(); i++) {
      mySubstitution.remove(correctOrder ? params2.get(i) : params1.get(i));
    }
    return result;
  }

  @Override
  public Boolean visitLam(LamExpression expr1, Expression expr2, Expression type) {
    return visitLam(expr1, expr2, type, true);
  }

  @Override
  public Boolean visitPi(PiExpression expr1, Expression expr2, Expression type) {
    PiExpression piExpr2 = expr2.cast(PiExpression.class);
    if (piExpr2 == null) {
      return false;
    }

    CMP origCMP = myCMP;
    myCMP = CMP.EQ;
    if (!compare(expr1.getParameters().getTypeExpr(), piExpr2.getParameters().getTypeExpr(), Type.OMEGA)) {
      return false;
    }

    SingleDependentLink link1 = expr1.getParameters(), link2 = piExpr2.getParameters();
    for (; link1.hasNext() && link2.hasNext(); link1 = link1.getNext(), link2 = link2.getNext()) {
      mySubstitution.put(link2, link1);
    }

    myCMP = origCMP;
    if (!compare(link1.hasNext() ? new PiExpression(expr1.getResultSort(), link1, expr1.getCodomain()) : expr1.getCodomain(), link2.hasNext() ? new PiExpression(piExpr2.getResultSort(), link2, piExpr2.getCodomain()) : piExpr2.getCodomain(), Type.OMEGA)) {
      return false;
    }

    for (DependentLink link = piExpr2.getParameters(); link != link2; link = link.getNext()) {
      mySubstitution.remove(link);
    }
    mySubstitution.remove(link2);
    return true;
  }

  public boolean compareParameters(DependentLink params1, DependentLink params2) {
    List<DependentLink> list1 = DependentLink.Helper.toList(params1);
    List<DependentLink> list2 = DependentLink.Helper.toList(params2);

    if (list1.size() != list2.size()) {
      return false;
    }

    CMP origCMP = myCMP;
    for (int i = 0; i < list1.size() && i < list2.size(); ++i) {
      if (!compare(list1.get(i).getTypeExpr(), list2.get(i).getTypeExpr(), Type.OMEGA)) {
        for (int j = 0; j < i; j++) {
          mySubstitution.remove(list2.get(j));
        }
        myCMP = origCMP;
        return false;
      }
      mySubstitution.put(list2.get(i), list1.get(i));
      myCMP = origCMP;
    }

    return true;
  }

  @Override
  public Boolean visitUniverse(UniverseExpression expr1, Expression expr2, Expression type) {
    UniverseExpression universe2 = expr2.cast(UniverseExpression.class);
    return universe2 != null && Sort.compare(expr1.getSort(), universe2.getSort(), myCMP, myNormalCompare ? myEquations : DummyEquations.getInstance(), mySourceNode);
  }

  @Override
  public Boolean visitError(ErrorExpression expr1, Expression expr2, Expression type) {
    return true;
  }

  @Override
  public Boolean visitTuple(TupleExpression expr1, Expression expr2, Expression type) {
    return visitTuple(expr1, expr2, true);
  }

  private Boolean visitTuple(TupleExpression expr1, Expression expr2, boolean correctOrder) {
    Expression type2 = expr2.getType();
    if (type2 == null || !compare(correctOrder ? expr1.getSigmaType() : type2, correctOrder ? type2 : expr1.getSigmaType(), Type.OMEGA)) {
      return false;
    }

    TupleExpression tuple2 = expr2.cast(TupleExpression.class);
    if (tuple2 != null) {
      return correctOrder ? compareLists(expr1.getFields(), tuple2.getFields(), expr1.getSigmaType().getParameters(), null, new ExprSubstitution()) : compareLists(tuple2.getFields(), expr1.getFields(), tuple2.getSigmaType().getParameters(), null, new ExprSubstitution());
    } else {
      List<Expression> args2 = new ArrayList<>(expr1.getFields().size());
      for (int i = 0; i < expr1.getFields().size(); i++) {
        args2.add(ProjExpression.make(expr2, i));
      }
      return correctOrder ? compareLists(expr1.getFields(), args2, expr1.getSigmaType().getParameters(), null, new ExprSubstitution()) : compareLists(args2, expr1.getFields(), expr1.getSigmaType().getParameters(), null, new ExprSubstitution());
    }
  }

  @Override
  public Boolean visitSigma(SigmaExpression expr1, Expression expr2, Expression type) {
    SigmaExpression sigma2 = expr2.cast(SigmaExpression.class);
    if (sigma2 == null) {
      return false;
    }
    if (!compareParameters(expr1.getParameters(), sigma2.getParameters())) {
      return false;
    }
    for (DependentLink link = sigma2.getParameters(); link.hasNext(); link = link.getNext()) {
      mySubstitution.remove(link);
    }
    return true;
  }

  @Override
  public Boolean visitProj(ProjExpression expr1, Expression expr2, Expression type) {
    ProjExpression proj2 = expr2.cast(ProjExpression.class);
    return proj2 != null && expr1.getField() == proj2.getField() && compare(expr1.getExpression(), proj2.getExpression(), null);
  }

  private boolean compareClassInstances(Expression expr1, ClassCallExpression classCall1, Expression expr2, ClassCallExpression classCall2, Expression type) {
    Set<? extends ClassField> fields = null;
    if (type != null) {
      ClassCallExpression classCall = type.cast(ClassCallExpression.class);
      if (classCall != null) {
        fields = classCall.getDefinition().getFields();
      }
    }
    if (fields == null) {
      fields = classCall1.getDefinition().getFields();
      if (classCall1.getDefinition() != classCall2.getDefinition()) {
        fields = new HashSet<>(fields);
        //noinspection SuspiciousMethodCalls
        fields.retainAll(classCall2.getDefinition().getFields());
      }
    }

    for (ClassField field : fields) {
      if (field.isProperty()) {
        continue;
      }

      Expression impl1 = classCall1.getImplementation(field, expr1);
      Expression impl2 = classCall2.getImplementation(field, expr2);
      if (impl1 == null && impl2 == null) {
        return false;
      }
      if (impl1 == null) {
        impl1 = FieldCallExpression.make(field, classCall1.getSortArgument(), expr1);
      }
      if (impl2 == null) {
        impl2 = FieldCallExpression.make(field, classCall2.getSortArgument(), expr2);
      }
      if (!compare(impl1, impl2, field.getType(classCall1.getSortArgument()).applyExpression(expr1))) {
        return false;
      }
    }

    return true;
  }

  @Override
  public Boolean visitNew(NewExpression expr1, Expression expr2, Expression type) {
    return false;
  }

  @Override
  public Boolean visitLet(LetExpression expr1, Expression expr2, Expression type) {
    throw new IllegalStateException();
  }

  public boolean compareLists(List<? extends Expression> list1, List<? extends Expression> list2, DependentLink link, Definition definition, ExprSubstitution substitution) {
    if (list1.size() != list2.size()) {
      return false;
    }

    CMP origCMP = myCMP;
    for (int i = 0; i < list1.size(); i++) {
      if (definition instanceof DataDefinition) {
        myCMP = ((DataDefinition) definition).isCovariant(i) ? origCMP : CMP.EQ;
      }
      if (!compare(list1.get(i), list2.get(i), substitution != null && link.hasNext() ? link.getTypeExpr().subst(substitution) : null)) {
        myCMP = origCMP;
        return false;
      }
      if (substitution != null && link.hasNext()) {
        substitution.add(link, (myCMP == CMP.LE ? list2 : list1).get(i));
        link = link.getNext();
      }
    }

    myCMP = origCMP;
    return true;
  }

  @Override
  public Boolean visitCase(CaseExpression case1, Expression expr2, Expression type) {
    CaseExpression case2 = expr2.cast(CaseExpression.class);
    if (case2 == null) {
      return false;
    }

    if (case1.getArguments().size() != case2.getArguments().size()) {
      return false;
    }

    if (!compareParameters(case1.getParameters(), case2.getParameters())) {
      return false;
    }

    if (!compare(case1.getResultType(), case2.getResultType(), Type.OMEGA)) {
      return false;
    }

    for (DependentLink link = case2.getParameters(); link.hasNext(); link = link.getNext()) {
      mySubstitution.remove(link);
    }

    if (!compareLists(case1.getArguments(), case2.getArguments(), case1.getParameters(), null, new ExprSubstitution())) {
      return false;
    }

    return compare(case1.getElimBody(), case2.getElimBody(), type);
  }

  @Override
  public Boolean visitOfType(OfTypeExpression expr, Expression expr2, Expression type) {
    return expr.getExpression().accept(this, expr2, type);
  }

  private boolean visitInteger(IntegerExpression expr1, Expression expr2) {
    IntegerExpression intExpr2 = expr2.cast(IntegerExpression.class);
    if (intExpr2 != null) {
      return expr1.isEqual(intExpr2);
    }

    ConCallExpression conCall2 = expr2.cast(ConCallExpression.class);
    Constructor constructor2 = conCall2 == null ? null : conCall2.getDefinition();
    if (constructor2 == null || !expr1.match(constructor2)) {
      return false;
    }
    if (constructor2 == Prelude.ZERO) {
      return true;
    }
    return compare(expr1.pred(), conCall2.getDefCallArguments().get(0), ExpressionFactory.Nat());
  }

  @Override
  public Boolean visitInteger(IntegerExpression expr, Expression expr2, Expression type) {
    return visitInteger(expr, expr2);
  }

  @Override
  public Boolean visitPEval(PEvalExpression expr, Expression other, Expression type) {
    return other.isInstance(PEvalExpression.class);
  }
}
