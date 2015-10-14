package com.jetbrains.jetpad.vclang.term.definition.visitor;

import com.jetbrains.jetpad.vclang.module.Namespace;
import com.jetbrains.jetpad.vclang.term.Abstract;
import com.jetbrains.jetpad.vclang.term.definition.NamespaceMember;
import com.jetbrains.jetpad.vclang.term.expr.arg.Utils;
import com.jetbrains.jetpad.vclang.term.expr.visitor.ResolveNameVisitor;
import com.jetbrains.jetpad.vclang.term.statement.visitor.StatementResolveNameVisitor;
import com.jetbrains.jetpad.vclang.typechecking.error.reporter.ErrorReporter;
import com.jetbrains.jetpad.vclang.typechecking.nameresolver.CompositeNameResolver;
import com.jetbrains.jetpad.vclang.typechecking.nameresolver.NameResolver;
import com.jetbrains.jetpad.vclang.typechecking.nameresolver.SingleNameResolver;

import java.util.ArrayList;
import java.util.List;

public class DefinitionResolveNameVisitor implements AbstractDefinitionVisitor<Boolean, Void> {
  private final ErrorReporter myErrorReporter;
  private final Namespace myNamespace;
  private final CompositeNameResolver myNameResolver;
  private List<String> myContext;

  public DefinitionResolveNameVisitor(ErrorReporter errorReporter, Namespace namespace, NameResolver nameResolver) {
    this(errorReporter, namespace, new CompositeNameResolver(), new ArrayList<String>());
    if (nameResolver != null) {
      myNameResolver.pushNameResolver(nameResolver);
    }
  }

  public DefinitionResolveNameVisitor(ErrorReporter errorReporter, Namespace namespace, CompositeNameResolver nameResolver, List<String> context) {
    myErrorReporter = errorReporter;
    myNamespace = namespace;
    myNameResolver = nameResolver;
    myContext = context;
  }

  @Override
  public Void visitFunction(Abstract.FunctionDefinition def, Boolean isStatic) {
    if (def.getStatements().isEmpty()) {
      visitFunction(def);
      return null;
    } else {
      try (StatementResolveNameVisitor statementVisitor = new StatementResolveNameVisitor(myErrorReporter, myNamespace.getChild(def.getName()), myNameResolver, myContext)) {
        for (Abstract.Statement statement : def.getStatements()) {
          statement.accept(statementVisitor, isStatic ? StatementResolveNameVisitor.Flag.MUST_BE_STATIC : null);
        }
        visitFunction(def);
      }
      return null;
    }
  }

  private void visitFunction(Abstract.FunctionDefinition def) {
    ResolveNameVisitor visitor = new ResolveNameVisitor(myErrorReporter, myNameResolver, myContext);
    try (Utils.ContextSaver ignored = new Utils.ContextSaver(myContext)) {
      for (Abstract.Argument argument : def.getArguments()) {
        if (argument instanceof Abstract.TypeArgument) {
          ((Abstract.TypeArgument) argument).getType().accept(visitor, null);
        }
        if (argument instanceof Abstract.TelescopeArgument) {
          myContext.addAll(((Abstract.TelescopeArgument) argument).getNames());
        } else
        if (argument instanceof Abstract.NameArgument) {
          myContext.add(((Abstract.NameArgument) argument).getName());
        }
      }

      if (def.getResultType() != null) {
        def.getResultType().accept(visitor, null);
      }

      if (def.getTerm() != null) {
        myNameResolver.pushNameResolver(new SingleNameResolver(def.getName().name, new NamespaceMember(myNamespace.getChild(def.getName()), def, null)));
        def.getTerm().accept(visitor, null);
        myNameResolver.popNameResolver();
      }
    }
  }

  @Override
  public Void visitData(Abstract.DataDefinition def, Boolean isStatic) {
    ResolveNameVisitor visitor = new ResolveNameVisitor(myErrorReporter, myNameResolver, myContext);

    try (Utils.CompleteContextSaver<String> saver = new Utils.CompleteContextSaver<>(myContext)) {
      for (Abstract.TypeArgument parameter : def.getParameters()) {
        parameter.getType().accept(visitor, null);
        if (parameter instanceof Abstract.TelescopeArgument) {
          myContext.addAll(((Abstract.TelescopeArgument) parameter).getNames());
        }
      }

      myNameResolver.pushNameResolver(new SingleNameResolver(def.getName().name, new NamespaceMember(myNamespace.getChild(def.getName()), def, null)));
      for (Abstract.Constructor constructor : def.getConstructors()) {
        if (constructor.getPatterns() == null) {
          visitConstructor(constructor, null);
        } else {
          myContext = saver.getOldContext();
          visitConstructor(constructor, null);
          myContext = saver.getCurrentContext();
        }
      }
      myNameResolver.popNameResolver();
    }

    return null;
  }

  @Override
  public Void visitConstructor(Abstract.Constructor def, Boolean isStatic) {
    try (Utils.ContextSaver ignored = new Utils.ContextSaver(myContext)) {
      ResolveNameVisitor visitor = new ResolveNameVisitor(myErrorReporter, myNameResolver, myContext);
      if (def.getPatterns() != null) {
        for (int i = 0; i < def.getPatterns().size(); ++i) {
          visitor.visitPattern(def, i);
        }
      }

      for (Abstract.TypeArgument argument : def.getArguments()) {
        argument.getType().accept(visitor, null);
        if (argument instanceof Abstract.TelescopeArgument) {
          myContext.addAll(((Abstract.TelescopeArgument) argument).getNames());
        }
      }
    }

    return null;
  }

  @Override
  public Void visitClass(Abstract.ClassDefinition def, Boolean isStatic) {
    try (StatementResolveNameVisitor visitor = new StatementResolveNameVisitor(myErrorReporter, myNamespace.getChild(def.getName()), myNameResolver, myContext)) {
      for (Abstract.Statement statement : def.getStatements()) {
        statement.accept(visitor, null);
      }
    }
    return null;
  }
}