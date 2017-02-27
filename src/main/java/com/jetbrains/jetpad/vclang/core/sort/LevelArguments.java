package com.jetbrains.jetpad.vclang.core.sort;

import com.jetbrains.jetpad.vclang.core.context.binding.LevelVariable;
import com.jetbrains.jetpad.vclang.core.context.binding.Variable;
import com.jetbrains.jetpad.vclang.core.context.binding.inference.InferenceLevelVariable;
import com.jetbrains.jetpad.vclang.core.subst.LevelSubstitution;
import com.jetbrains.jetpad.vclang.term.Abstract;
import com.jetbrains.jetpad.vclang.typechecking.implicitargs.equations.Equations;

import java.util.HashMap;
import java.util.Map;

public class LevelArguments {
  private final Level myPLevel;
  private final Level myHLevel;

  public static final LevelArguments STD = new LevelArguments(new Level(LevelVariable.PVAR), new Level(LevelVariable.HVAR));
  public static final LevelArguments ZERO = new LevelArguments(new Level(0), new Level(0));

  public LevelArguments(Level pLevel, Level hLevel) {
    myPLevel = pLevel;
    myHLevel = hLevel;
  }

  public Level getPLevel()  {
    return myPLevel;
  }

  public Level getHLevel()  {
    return myHLevel;
  }

  public LevelSubstitution toLevelSubstitution() {
    Map<Variable, Level> polySubst = new HashMap<>();
    polySubst.put(LevelVariable.PVAR, myPLevel);
    polySubst.put(LevelVariable.HVAR, myHLevel);
    return new LevelSubstitution(polySubst);
  }

  public LevelArguments subst(LevelSubstitution subst) {
    return new LevelArguments(myPLevel.subst(subst), myHLevel.subst(subst));
  }

  public static LevelArguments generateInferVars(Equations equations, Abstract.Expression expr) {
    InferenceLevelVariable pl = new InferenceLevelVariable(LevelVariable.LvlType.PLVL, expr);
    InferenceLevelVariable hl = new InferenceLevelVariable(LevelVariable.LvlType.HLVL, expr);
    equations.addVariable(pl);
    equations.addVariable(hl);
    return new LevelArguments(new Level(pl), new Level(hl));
  }
}
