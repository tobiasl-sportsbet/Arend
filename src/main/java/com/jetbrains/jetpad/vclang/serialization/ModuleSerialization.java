package com.jetbrains.jetpad.vclang.serialization;

import com.jetbrains.jetpad.vclang.module.Module;
import com.jetbrains.jetpad.vclang.module.ModuleLoader;
import com.jetbrains.jetpad.vclang.term.Abstract;
import com.jetbrains.jetpad.vclang.term.definition.*;
import com.jetbrains.jetpad.vclang.term.error.TypeCheckingError;
import com.jetbrains.jetpad.vclang.term.expr.*;
import com.jetbrains.jetpad.vclang.term.expr.arg.Argument;
import com.jetbrains.jetpad.vclang.term.expr.arg.NameArgument;
import com.jetbrains.jetpad.vclang.term.expr.arg.TelescopeArgument;
import com.jetbrains.jetpad.vclang.term.expr.arg.TypeArgument;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

import static com.jetbrains.jetpad.vclang.term.expr.ExpressionFactory.*;
import static com.jetbrains.jetpad.vclang.term.expr.ExpressionFactory.Error;

public class ModuleSerialization {
  private static final byte[] SIGNATURE = { 'v', 'c', (byte) 0xb1, 0x0b };
  private static final int VERSION = 0;

  public static void writeFile(ClassDefinition def, File outputFile) throws IOException {
    Files.createDirectories(outputFile.getParentFile().toPath());
    ByteArrayOutputStream byteArrayStream = new ByteArrayOutputStream();
    DataOutputStream dataStream = new DataOutputStream(byteArrayStream);
    DefinitionsIndices definitionsIndices = new DefinitionsIndices();
    SerializeVisitor visitor = new SerializeVisitor(definitionsIndices, byteArrayStream, dataStream);
    int errors = serializeClassDefinition(visitor, def);

    DataOutputStream fileStream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile)));
    fileStream.write(SIGNATURE);
    fileStream.writeInt(VERSION);
    fileStream.writeInt(errors + visitor.getErrors());
    definitionsIndices.serialize(fileStream);
    byteArrayStream.writeTo(fileStream);
    fileStream.close();
  }

  public static int readFile(File file, ClassDefinition module) throws IOException, DeserializationException {
    DataInputStream stream = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
    byte[] signature = new byte[4];
    stream.readFully(signature);
    if (!Arrays.equals(signature, SIGNATURE)) {
      throw new IncorrectFormat();
    }
    int version = stream.readInt();
    if (version != VERSION) {
      throw new WrongVersion(version);
    }
    int errorsNumber = stream.readInt();

    Map<Integer, Definition> definitionMap = new HashMap<>();
    definitionMap.put(0, ModuleLoader.getInstance().rootModule());
    int size = stream.readInt();
    for (int i = 0; i < size; ++i) {
      int index = stream.readInt();
      Definition childModule;
      if (index == 0) {
        childModule = ModuleLoader.getInstance().rootModule();
      } else {
        int parentIndex = stream.readInt();
        String name = stream.readUTF();
        int code = stream.read();

        Definition parent = definitionMap.get(parentIndex);
        if (parent == null) {
          throw new IncorrectFormat();
        }
        childModule = parent.findChild(name);

        if (childModule == null) {
          if (parent instanceof ClassDefinition && code == CLASS_CODE) {
            childModule = ModuleLoader.getInstance().loadModule(new Module((ClassDefinition) parent, name));
          } else
          if (parent.isDescendantOf(module)) {
            childModule = newDefinition(code, name, parent);
            module.add(childModule);
          } else {
            throw new DeserializationException(name + " is not defined in " + parent.getFullName());
          }
        }
      }

      definitionMap.put(index, childModule);
    }

    deserializeClassDefinition(stream, definitionMap, module);
    return errorsNumber;
  }

  private static int serializeDefinition(SerializeVisitor visitor, Definition definition) throws IOException {
    if (definition instanceof Constructor) return 0;
    visitor.getDataStream().write(getDefinitionCode(definition));
    visitor.getDataStream().writeInt(visitor.getDefinitionsIndices().getDefinitionIndex(definition));
    visitor.getDataStream().writeBoolean(definition.hasErrors());

    if (definition instanceof FunctionDefinition) {
      FunctionDefinition functionDefinition = (FunctionDefinition) definition;
      writeDefinition(visitor.getDataStream(), definition);
      visitor.getDataStream().writeBoolean(functionDefinition.typeHasErrors());
      if (!functionDefinition.typeHasErrors()) {
        writeArguments(visitor, functionDefinition.getArguments());
        functionDefinition.getResultType().accept(visitor);
      }
      visitor.getDataStream().writeBoolean(functionDefinition.getArrow() == Abstract.Definition.Arrow.RIGHT);
      if (!definition.hasErrors()) {
        functionDefinition.getTerm().accept(visitor);
      }
      return definition.hasErrors() ? 1 : 0;
    } else
    if (definition instanceof DataDefinition) {
      int errors = definition.hasErrors() ? 1 : 0;
      DataDefinition dataDefinition = (DataDefinition) definition;
      writeDefinition(visitor.getDataStream(), definition);
      if (!definition.hasErrors()) {
        writeUniverse(visitor.getDataStream(), definition.getUniverse());
        writeArguments(visitor, dataDefinition.getParameters());
      }
      visitor.getDataStream().writeInt(dataDefinition.getConstructors().size());
      for (Constructor constructor : dataDefinition.getConstructors()) {
        visitor.getDataStream().writeInt(visitor.getDefinitionsIndices().getDefinitionIndex(constructor));
        visitor.getDataStream().writeBoolean(constructor.hasErrors());
        writeDefinition(visitor.getDataStream(), constructor);
        if (!constructor.hasErrors()) {
          writeUniverse(visitor.getDataStream(), constructor.getUniverse());
          writeArguments(visitor, constructor.getArguments());
        } else {
          errors += 1;
        }
      }
      return errors;
    } else
    if (definition instanceof ClassDefinition) {
      return serializeClassDefinition(visitor, (ClassDefinition) definition);
    } else {
      throw new IllegalStateException();
    }
  }

  public static int FUNCTION_CODE = 0;
  public static int DATA_CODE = 1;
  public static int CLASS_CODE = 2;
  public static int CONSTRUCTOR_CODE = 3;

  public static int getDefinitionCode(Definition definition) {
    if (definition instanceof FunctionDefinition) return FUNCTION_CODE;
    if (definition instanceof DataDefinition) return DATA_CODE;
    if (definition instanceof ClassDefinition) return CLASS_CODE;
    if (definition instanceof Constructor) return CONSTRUCTOR_CODE;
    throw new IllegalStateException();
  }

  public static Definition newDefinition(int code, String name, Definition parent) throws IncorrectFormat {
    if (code == 0) return new FunctionDefinition(name, parent, Abstract.Definition.DEFAULT_PRECEDENCE, Abstract.Definition.Fixity.PREFIX, Abstract.Definition.Arrow.LEFT);
    if (code == 1) return new DataDefinition(name, parent, Abstract.Definition.DEFAULT_PRECEDENCE, Abstract.Definition.Fixity.PREFIX, new ArrayList<Constructor>());
    if (code == 2) return new ClassDefinition(name, parent);
    if (code == 3) return new Constructor(-1, name, parent, Abstract.Definition.DEFAULT_PRECEDENCE, Abstract.Definition.Fixity.PREFIX);
    throw new IncorrectFormat();
  }

  private static void deserializeDefinition(DataInputStream stream, Map<Integer, Definition> definitionMap) throws IOException, DeserializationException {
    int code = stream.read();
    Definition definition = definitionMap.get(stream.readInt());
    definition.hasErrors(stream.readBoolean());

    if (code == FUNCTION_CODE) {
      if (!(definition instanceof FunctionDefinition)) {
        throw new IncorrectFormat();
      }
      readDefinition(stream, definition);

      FunctionDefinition functionDefinition = (FunctionDefinition) definition;
      functionDefinition.typeHasErrors(stream.readBoolean());
      if (!functionDefinition.typeHasErrors()) {
        functionDefinition.setArguments(readTelescopeArguments(stream, definitionMap));
        functionDefinition.setResultType(readExpression(stream, definitionMap));
      }
      functionDefinition.setArrow(stream.readBoolean() ? Abstract.Definition.Arrow.RIGHT : Abstract.Definition.Arrow.LEFT);
      if (!functionDefinition.hasErrors()) {
        functionDefinition.setTerm(readExpression(stream, definitionMap));
      }
    } else
    if (code == DATA_CODE) {
      if (!(definition instanceof DataDefinition)) {
        throw new IncorrectFormat();
      }
      readDefinition(stream, definition);

      DataDefinition dataDefinition = (DataDefinition) definition;
      if (!dataDefinition.hasErrors()) {
        dataDefinition.setUniverse(readUniverse(stream));
        dataDefinition.setParameters(readTypeArguments(stream, definitionMap));
      }
      int constructorsNumber = stream.readInt();
      dataDefinition.setConstructors(new ArrayList<Constructor>(constructorsNumber));
      for (int i = 0; i < constructorsNumber; ++i) {
        Constructor constructor = (Constructor) definitionMap.get(stream.readInt());
        if (constructor == null) {
          throw new IncorrectFormat();
        }
        constructor.setIndex(i);
        constructor.hasErrors(stream.readBoolean());
        readDefinition(stream, constructor);

        if (!constructor.hasErrors()) {
          constructor.setUniverse(readUniverse(stream));
          constructor.setArguments(readTypeArguments(stream, definitionMap));
        }
      }
    } else
    if (code == CLASS_CODE) {
      if (!(definition instanceof ClassDefinition)) {
        throw new IncorrectFormat();
      }

      ClassDefinition classDefinition = (ClassDefinition) definition;
      deserializeClassDefinition(stream, definitionMap, classDefinition);
    } else {
      throw new IncorrectFormat();
    }
  }

  private static int serializeClassDefinition(SerializeVisitor visitor, ClassDefinition definition) throws IOException {
    writeUniverse(visitor.getDataStream(), definition.getUniverse());
    int size = 0;
    List<Definition> fields = definition.getFields();
    for (Definition field : fields) {
      if (!(field instanceof Constructor)) {
        ++size;
      }
    }
    visitor.getDataStream().writeInt(size);
    int errors = 0;
    for (Definition field : fields) {
      errors += serializeDefinition(visitor, field);
    }
    return errors;
  }

  private static void deserializeClassDefinition(DataInputStream stream, Map<Integer, Definition> definitionMap, ClassDefinition definition) throws IOException, DeserializationException {
    Universe universe = readUniverse(stream);
    int size = stream.readInt();
    definition.setUniverse(universe);
    for (int i = 0; i < size; ++i) {
      deserializeDefinition(stream, definitionMap);
    }
  }

  private static void writeDefinition(DataOutputStream stream, Definition definition) throws IOException {
    stream.write(definition.getPrecedence().associativity == Abstract.Definition.Associativity.LEFT_ASSOC ? 0 : definition.getPrecedence().associativity == Abstract.Definition.Associativity.RIGHT_ASSOC ? 1 : 2);
    stream.writeByte(definition.getPrecedence().priority);
    stream.write(definition.getFixity() == Abstract.Definition.Fixity.PREFIX ? 1 : 0);
  }

  private static void readDefinition(DataInputStream stream, Definition definition) throws IncorrectFormat, IOException {
    int assocCode = stream.read();
    Abstract.Definition.Associativity assoc;
    if (assocCode == 0) {
      assoc = Abstract.Definition.Associativity.LEFT_ASSOC;
    } else
    if (assocCode == 1) {
      assoc = Abstract.Definition.Associativity.RIGHT_ASSOC;
    } else
    if (assocCode == 2) {
      assoc = Abstract.Definition.Associativity.NON_ASSOC;
    } else {
      throw new IncorrectFormat();
    }
    definition.setPrecedence(new Abstract.Definition.Precedence(assoc, stream.readByte()));

    int fixityCode = stream.read();
    if (fixityCode == 0) {
      definition.setFixity(Abstract.Definition.Fixity.INFIX);
    } else
    if (fixityCode == 1) {
      definition.setFixity(Abstract.Definition.Fixity.PREFIX);
    } else {
      throw new IncorrectFormat();
    }
  }

  public static void writeUniverse(DataOutputStream stream, Universe universe) throws IOException {
    stream.writeInt(universe.getLevel());
    if (universe instanceof Universe.Type) {
      stream.writeInt(((Universe.Type) universe).getTruncated());
    } else {
      throw new IllegalStateException();
    }
  }

  public static Universe readUniverse(DataInputStream stream) throws IOException {
    int level = stream.readInt();
    int truncated = stream.readInt();
    return new Universe.Type(level, truncated);
  }

  public static void writeArguments(SerializeVisitor visitor, List<? extends Argument> arguments) throws IOException {
    visitor.getDataStream().writeInt(arguments.size());
    for (Argument argument : arguments) {
      writeArgument(visitor, argument);
    }
  }

  public static List<Argument> readArguments(DataInputStream stream, Map<Integer, Definition> definitionMap) throws IOException, IncorrectFormat {
    int size = stream.readInt();
    List<Argument> result = new ArrayList<>(size);
    for (int i = 0; i < size; ++i) {
      result.add(readArgument(stream, definitionMap));
    }
    return result;
  }

  public static List<NameArgument> readNameArguments(DataInputStream stream, Map<Integer, Definition> definitionMap) throws IOException, IncorrectFormat {
    int size = stream.readInt();
    List<NameArgument> result = new ArrayList<>(size);
    for (int i = 0; i < size; ++i) {
      Argument argument = readArgument(stream, definitionMap);
      if (!(argument instanceof NameArgument)) {
        throw new IncorrectFormat();
      }
      result.add((NameArgument) argument);
    }
    return result;
  }

  public static List<TypeArgument> readTypeArguments(DataInputStream stream, Map<Integer, Definition> definitionMap) throws IOException, IncorrectFormat {
    int size = stream.readInt();
    List<TypeArgument> result = new ArrayList<>(size);
    for (int i = 0; i < size; ++i) {
      Argument argument = readArgument(stream, definitionMap);
      if (!(argument instanceof TypeArgument)) {
        throw new IncorrectFormat();
      }
      result.add((TypeArgument) argument);
    }
    return result;
  }

  public static List<TelescopeArgument> readTelescopeArguments(DataInputStream stream, Map<Integer, Definition> definitionMap) throws IOException, IncorrectFormat {
    int size = stream.readInt();
    List<TelescopeArgument> result = new ArrayList<>(size);
    for (int i = 0; i < size; ++i) {
      Argument argument = readArgument(stream, definitionMap);
      if (!(argument instanceof TelescopeArgument)) {
        throw new IncorrectFormat();
      }
      result.add((TelescopeArgument) argument);
    }
    return result;
  }

  public static void writeArgument(SerializeVisitor visitor, Argument argument) throws IOException {
    visitor.getDataStream().writeBoolean(argument.getExplicit());
    if (argument instanceof TelescopeArgument) {
      visitor.getDataStream().write(0);
      visitor.getDataStream().writeInt(((TelescopeArgument) argument).getNames().size());
      for (String name : ((TelescopeArgument) argument).getNames()) {
        visitor.getDataStream().writeBoolean(name != null);
        if (name != null) {
          visitor.getDataStream().writeUTF(name);
        }
      }
      ((TypeArgument) argument).getType().accept(visitor);
    } else
    if (argument instanceof TypeArgument) {
      visitor.getDataStream().write(1);
      ((TypeArgument) argument).getType().accept(visitor);
    } else
    if (argument instanceof NameArgument) {
      visitor.getDataStream().write(2);
      String name = ((NameArgument) argument).getName();
      visitor.getDataStream().writeBoolean(name != null);
      if (name != null) {
        visitor.getDataStream().writeUTF(name);
      }
    } else {
      throw new IllegalStateException();
    }
  }

  public static Argument readArgument(DataInputStream stream, Map<Integer, Definition> definitionMap) throws IOException, IncorrectFormat {
    boolean explicit = stream.readBoolean();
    int code = stream.read();
    if (code == 0) {
      int size = stream.readInt();
      List<String> names = new ArrayList<>(size);
      for (int i = 0; i < size; ++i) {
        names.add(stream.readBoolean() ? stream.readUTF() : null);
      }
      return new TelescopeArgument(explicit, names, readExpression(stream, definitionMap));
    } else
    if (code == 1) {
      return new TypeArgument(explicit, readExpression(stream, definitionMap));
    } else
    if (code == 2) {
      return new NameArgument(explicit, stream.readBoolean() ? stream.readUTF() : null);
    } else {
      throw new IncorrectFormat();
    }
  }

  public static Expression readExpression(DataInputStream stream, Map<Integer, Definition> definitionMap) throws IOException, IncorrectFormat {
    int code = stream.read();
    switch (code) {
      case 1: {
        Expression function = readExpression(stream, definitionMap);
        boolean explicit = stream.readBoolean();
        boolean hidden = stream.readBoolean();
        Expression argument = readExpression(stream, definitionMap);
        return Apps(function, new ArgumentExpression(argument, explicit, hidden));
      }
      case 2: {
        Definition definition = definitionMap.get(stream.readInt());
        if (definition == null) {
          throw new IncorrectFormat();
        }
        return DefCall(definition);
      }
      case 3: {
        return Index(stream.readInt());
      }
      case 4: {
        Expression body = readExpression(stream, definitionMap);
        return Lam(readArguments(stream, definitionMap), body);
      }
      case 5: {
        List<TypeArgument> arguments = readTypeArguments(stream, definitionMap);
        return Pi(arguments, readExpression(stream, definitionMap));
      }
      case 6: {
        return new UniverseExpression(readUniverse(stream));
      }
      case 9: {
        return Error(stream.readBoolean() ? readExpression(stream, definitionMap) : null, new TypeCheckingError("Deserialized error", null, null));
      }
      case 10: {
        int size = stream.readInt();
        List<Expression> fields = new ArrayList<>(size);
        for (int i = 0; i < size; ++i) {
          fields.add(readExpression(stream, definitionMap));
        }
        return new TupleExpression(fields);
      }
      case 11: {
        return Sigma(readTypeArguments(stream, definitionMap));
      }
      case 12: {
        Abstract.ElimExpression.ElimType elimType = stream.readBoolean() ? Abstract.ElimExpression.ElimType.ELIM : Abstract.ElimExpression.ElimType.CASE;
        int index = stream.readInt();
        int clausesNumber = stream.readInt();
        List<Clause> clauses = new ArrayList<>(clausesNumber);
        for (int i = 0; i < clausesNumber; ++i) {
          clauses.add(readClause(stream, definitionMap));
        }
        ElimExpression result = Elim(elimType, Index(index), clauses, stream.readBoolean() ? readClause(stream, definitionMap) : null);
        for (Clause clause : result.getClauses()) {
          clause.setElimExpression(result);
        }
        if (result.getOtherwise() != null) {
          result.getOtherwise().setElimExpression(result);
        }
        return result;
      }
      case 13: {
        Expression expr = readExpression(stream, definitionMap);
        Definition definition = definitionMap.get(stream.readInt());
        if (definition == null) {
          throw new IncorrectFormat();
        }
        return FieldAcc(expr, definition);
      }
      case 14: {
        Expression expr = readExpression(stream, definitionMap);
        return Proj(expr, stream.readInt());
      }
      default: {
        throw new IncorrectFormat();
      }
    }
  }

  public static Clause readClause(DataInputStream stream, Map<Integer, Definition> definitionMap) throws IOException, IncorrectFormat {
    Definition definition = definitionMap.get(stream.readInt());
    if (!(definition instanceof Constructor)) {
      throw new IncorrectFormat();
    }
    List<NameArgument> arguments = readNameArguments(stream, definitionMap);
    Abstract.Definition.Arrow arrow = stream.readBoolean() ? Abstract.Definition.Arrow.RIGHT : Abstract.Definition.Arrow.LEFT;
    return new Clause((Constructor) definition, arguments, arrow, readExpression(stream, definitionMap), null);
  }

  public static class DeserializationException extends Exception {
    private final String myMessage;

    public DeserializationException(String message) {
      myMessage = message;
    }

    @Override
    public String toString() {
      return myMessage;
    }
  }

  public static class IncorrectFormat extends DeserializationException {
    public IncorrectFormat() {
      super("Incorrect format");
    }
  }

  public static class WrongVersion extends DeserializationException {
    WrongVersion(int version) {
      super("Version of the file format (" + version + ") differs from the version of the program + (" + VERSION + ")");
    }
  }
}