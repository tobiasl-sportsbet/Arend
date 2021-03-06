package org.arend.extImpl.definitionContributor;

import org.arend.ext.DefinitionContributor;
import org.arend.ext.error.ErrorReporter;
import org.arend.ext.module.LongName;
import org.arend.ext.module.ModulePath;
import org.arend.ext.reference.Precedence;
import org.arend.ext.typechecking.MetaDefinition;
import org.arend.extImpl.Disableable;
import org.arend.library.Library;
import org.arend.library.error.LibraryError;
import org.arend.module.scopeprovider.SimpleModuleScopeProvider;
import org.arend.naming.reference.MetaReferable;
import org.arend.naming.reference.Referable;
import org.arend.naming.scope.SimpleScope;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class DefinitionContributorImpl extends Disableable implements DefinitionContributor {
  private final Library myLibrary;
  private final ErrorReporter myErrorReporter;
  private final SimpleModuleScopeProvider myModuleScopeProvider;
  private final DefinitionContributor myDefinitionContributor;

  public DefinitionContributorImpl(Library library, ErrorReporter errorReporter, SimpleModuleScopeProvider moduleScopeProvider, DefinitionContributor definitionContributor) {
    myLibrary = library;
    myErrorReporter = errorReporter;
    myModuleScopeProvider = moduleScopeProvider;
    myDefinitionContributor = definitionContributor;
  }

  @Override
  public void declare(@NotNull ModulePath module, @NotNull LongName longName, @NotNull Precedence precedence, @NotNull MetaDefinition meta) {
    checkEnabled();
    myDefinitionContributor.declare(module, longName, precedence, meta);

    SimpleScope scope = (SimpleScope) myModuleScopeProvider.forModule(module);
    if (scope == null) {
      scope = new SimpleScope();
      myModuleScopeProvider.addModule(module, scope);
    }

    List<String> list = longName.toList();
    for (int i = 0; i < list.size(); i++) {
      String name = list.get(i);
      if (i == list.size() - 1) {
        Referable ref = scope.resolveName(name);
        if (ref instanceof MetaReferable && ((MetaReferable) ref).getDefinition() == null) {
          ((MetaReferable) ref).setPrecedence(precedence);
          ((MetaReferable) ref).setDefinition(meta);
          return;
        }
        if (ref != null) {
          myErrorReporter.report(LibraryError.duplicateExtensionDefinition(myLibrary.getName(), module, longName));
          return;
        }
        scope.names.put(name, new MetaReferable(precedence, name, meta));
      } else {
        scope.names.put(name, new MetaReferable(Precedence.DEFAULT, name, null));
        scope = scope.namespaces.computeIfAbsent(name, k -> new SimpleScope());
      }
    }
  }
}
