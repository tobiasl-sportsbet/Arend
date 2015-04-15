package com.jetbrains.jetpad.vclang.term.definition;

import com.jetbrains.jetpad.vclang.term.error.TypeCheckingError;
import com.jetbrains.jetpad.vclang.term.expr.Abstract;
import com.jetbrains.jetpad.vclang.term.expr.Expression;
import com.jetbrains.jetpad.vclang.term.expr.arg.TelescopeArgument;
import com.jetbrains.jetpad.vclang.term.expr.arg.TypeArgument;
import com.jetbrains.jetpad.vclang.term.visitor.CheckTypeVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.jetbrains.jetpad.vclang.term.expr.ExpressionFactory.*;
import static com.jetbrains.jetpad.vclang.term.expr.arg.Utils.*;

public class Constructor extends Definition {
  private final DataDefinition myDataType;
  private final List<TypeArgument> myArguments;

  protected Constructor(int id, String name, Precedence precedence, Fixity fixity, List<TypeArgument> arguments, DataDefinition dataType) {
    super(id, name, precedence, fixity);
    myArguments = arguments;
    myDataType = dataType;
  }

  public Constructor(String name, Precedence precedence, Fixity fixity, List<TypeArgument> arguments, DataDefinition dataType) {
    super(name, precedence, fixity);
    myArguments = arguments;
    myDataType = dataType;
  }

  public Constructor(String name, Fixity fixity, List<TypeArgument> arguments, DataDefinition dataType) {
    super(name, fixity);
    myArguments = arguments;
    myDataType = dataType;
  }

  public Constructor(String name, List<TypeArgument> arguments, DataDefinition dataType) {
    super(name);
    myArguments = arguments;
    myDataType = dataType;
  }

  public DataDefinition getDataType() {
    return myDataType;
  }

  @Override
  public void prettyPrint(StringBuilder builder, List<String> names, byte prec) {
    builder.append(getName());
    for (TypeArgument argument : myArguments) {
      builder.append(' ');
      argument.prettyPrint(builder, names, Abstract.VarExpression.PREC);
    }
    removeFromList(names, myArguments);
  }

  @Override
  public Constructor checkTypes(Map<String, Definition> globalContext, List<Binding> localContext, List<TypeCheckingError> errors) {
    List<TypeArgument> arguments = new ArrayList<>(myArguments.size());
    int origSize = localContext.size();
    for (TypeArgument argument : myArguments) {
      CheckTypeVisitor.OKResult result = argument.getType().checkType(globalContext, localContext, Universe(-1), errors);
      if (result == null) {
        trimToSize(localContext, origSize);
        return null;
      }
      if (argument instanceof TelescopeArgument) {
        arguments.add(Tele(argument.getExplicit(), ((TelescopeArgument) argument).getNames(), result.expression));
        for (String name : ((TelescopeArgument) argument).getNames()) {
          localContext.add(new TypedBinding(name, result.expression));
        }
      } else {
        arguments.add(TypeArg(argument.getExplicit(), result.expression));
        localContext.add(new TypedBinding(null, result.expression));
      }
    }

    trimToSize(localContext, origSize);
    return new Constructor(myID, getName(), getPrecedence(), getFixity(), arguments, myDataType);
  }

  @Override
  public Expression getType() {
    Expression resultType = DefCall(myDataType);
    int numberOfVars = numberOfVariables(myArguments);
    for (int i = numberOfVariables(myDataType.getParameters()) - 1, j = 0; i >= 0; ++j) {
      if (myDataType.getParameter(j) instanceof TelescopeArgument) {
        for (String ignored : ((TelescopeArgument) myDataType.getParameter(j)).getNames()) {
          resultType = App(resultType, Index(i-- + numberOfVars), myDataType.getParameter(j).getExplicit());
        }
      } else {
        resultType = App(resultType, Index(i-- + numberOfVars), myDataType.getParameter(j).getExplicit());
      }
    }
    return myArguments.isEmpty() ? resultType : Pi(myArguments, resultType);
  }
}