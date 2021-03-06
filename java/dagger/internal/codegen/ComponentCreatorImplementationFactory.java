/*
 * Copyright (C) 2017 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static com.google.auto.common.MoreTypes.asDeclared;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.squareup.javapoet.MethodSpec.constructorBuilder;
import static com.squareup.javapoet.MethodSpec.methodBuilder;
import static com.squareup.javapoet.TypeSpec.classBuilder;
import static dagger.internal.codegen.CodeBlocks.toParametersCodeBlock;
import static dagger.internal.codegen.ModuleProxies.newModuleInstance;
import static dagger.internal.codegen.SourceFiles.simpleVariableName;
import static dagger.internal.codegen.TypeSpecs.addSupertype;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PROTECTED;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dagger.internal.Preconditions;
import dagger.internal.codegen.ComponentRequirement.NullPolicy;
import java.util.Optional;
import javax.inject.Inject;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;

/** Factory for creating {@link ComponentCreatorImplementation} instances. */
final class ComponentCreatorImplementationFactory {

  private final DaggerElements elements;
  private final DaggerTypes types;

  @Inject
  ComponentCreatorImplementationFactory(DaggerElements elements, DaggerTypes types) {
    this.elements = elements;
    this.types = types;
  }

  /** Returns a new creator implementation for the given component, if necessary. */
  Optional<ComponentCreatorImplementation> create(ComponentImplementation componentImplementation) {
    if (!componentImplementation.graph().componentDescriptor().hasCreator()) {
      return Optional.empty();
    }

    if (componentImplementation.superclassImplementation().isPresent()
        && componentImplementation.isAbstract()) {
      // The component builder in ahead-of-time mode is generated with the base subcomponent
      // implementation, with the exception of the build method since that requires invoking the
      // constructor of a subclass component implementation. Intermediate component implementations,
      // because they still can't invoke the eventual constructor and have no additional extensions
      // to the builder, can ignore generating a builder implementation.
      return Optional.empty();
    }

    Builder builder =
        componentImplementation.graph().componentDescriptor().creatorDescriptor().isPresent()
            ? new BuilderForCreatorDescriptor(componentImplementation)
            : new BuilderForGeneratedRootComponentBuilder(componentImplementation);
    return Optional.of(builder.build());
  }

  /** Base class for building a creator implementation. */
  private abstract class Builder {
    final ComponentImplementation componentImplementation;
    final ClassName className;
    final TypeSpec.Builder classBuilder;

    private ImmutableMap<ComponentRequirement, FieldSpec> fields;

    Builder(ComponentImplementation componentImplementation) {
      this.componentImplementation = componentImplementation;
      this.className = componentImplementation.getCreatorName();
      this.classBuilder = classBuilder(className);
    }

    /** Builds the {@link ComponentCreatorImplementation}. */
    ComponentCreatorImplementation build() {
      setModifiers();
      setSupertype();
      this.fields = getOrAddFields();
      addConstructor();
      addSetterMethods();
      addFactoryMethod();
      return ComponentCreatorImplementation.create(
          classBuilder.build(), className, providedRequirements(), fields);
    }

    /** Returns the binding graph for the component. */
    final BindingGraph graph() {
      return componentImplementation.graph();
    }

    /**
     * The component requirements that this creator will actually provide when constructing a
     * component.
     */
    final ImmutableSet<ComponentRequirement> providedRequirements() {
      return Sets.intersection(fields.keySet(), componentImplementation.requirements())
          .immutableCopy();
    }

    /** The {@link ComponentRequirement}s that this creator can set. */
    abstract ImmutableMap<ComponentRequirement, RequirementStatus> settableRequirements();

    private void setModifiers() {
      classBuilder.addModifiers(visibility());
      if (!componentImplementation.isNested()) {
        classBuilder.addModifiers(STATIC);
      }
      classBuilder.addModifiers(componentImplementation.isAbstract() ? ABSTRACT : FINAL);
    }

    /** Returns the visibility modifier the generated class should have. */
    protected abstract Modifier visibility();

    /** Sets the superclass being extended or interface being implemented for this creator. */
    protected abstract void setSupertype();

    /** Adds a constructor for the creator type, if needed. */
    protected abstract void addConstructor();

    private ImmutableMap<ComponentRequirement, FieldSpec> getOrAddFields() {
      // If a base implementation is present, any fields are already defined there and don't need to
      // be created in this implementation.
      return componentImplementation
        .baseCreatorImplementation()
        .map(ComponentCreatorImplementation::fields)
        .orElseGet(() -> addFields());
    }

    private ImmutableMap<ComponentRequirement, FieldSpec> addFields() {
      // Fields in an abstract creator class need to be visible from subclasses.
      Modifier modifier = componentImplementation.isAbstract() ? PROTECTED : PRIVATE;
      UniqueNameSet fieldNames = new UniqueNameSet();
      ImmutableMap<ComponentRequirement, FieldSpec> result =
          Maps.toMap(
              componentImplementation.requirements(),
              requirement ->
                  FieldSpec.builder(
                          TypeName.get(requirement.type()),
                          fieldNames.getUniqueName(requirement.variableName()),
                          modifier)
                      .build());
      classBuilder.addFields(result.values());
      return result;
    }

    private void addSetterMethods() {
      settableRequirements()
          .forEach(
              (requirement, status) ->
                  createSetterMethod(requirement, status).ifPresent(classBuilder::addMethod));
    }

    /** Creates a new setter method builder, with no method body, for the given requirement. */
    protected abstract MethodSpec.Builder setterMethodBuilder(ComponentRequirement requirement);

    private Optional<MethodSpec> createSetterMethod(
        ComponentRequirement requirement, RequirementStatus status) {
      switch (status) {
        case NEEDED:
          return Optional.of(normalSetterMethod(requirement));
        case UNNEEDED:
          return Optional.of(noopSetterMethod(requirement));
        case UNSETTABLE_REPEATED_MODULE:
          return Optional.of(repeatedModuleSetterMethod(requirement));
        case IMPLEMENTED_IN_SUPERTYPE:
          return Optional.empty();
      }
      throw new AssertionError();
    }

    private MethodSpec normalSetterMethod(ComponentRequirement requirement) {
      MethodSpec.Builder method = setterMethodBuilder(requirement);
      ParameterSpec parameter = parameter(method.build());
      method.addStatement(
          "this.$N = $L",
          fields.get(requirement),
          requirement.nullPolicy(elements, types).equals(NullPolicy.ALLOW)
              ? CodeBlock.of("$N", parameter)
              : CodeBlock.of("$T.checkNotNull($N)", Preconditions.class, parameter));
      return maybeReturnThis(method);
    }

    private MethodSpec noopSetterMethod(ComponentRequirement requirement) {
      MethodSpec.Builder method = setterMethodBuilder(requirement);
      ParameterSpec parameter = parameter(method.build());
      method
          .addAnnotation(Deprecated.class)
          .addJavadoc(
              "@deprecated This module is declared, but an instance is not used in the component. "
                  + "This method is a no-op. For more, see https://google.github.io/dagger/unused-modules.\n")
          .addStatement("$T.checkNotNull($N)", Preconditions.class, parameter);
      return maybeReturnThis(method);
    }

    private MethodSpec repeatedModuleSetterMethod(ComponentRequirement requirement) {
      return setterMethodBuilder(requirement)
          .addStatement(
              "throw new $T($T.format($S, $T.class.getCanonicalName()))",
              UnsupportedOperationException.class,
              String.class,
              "%s cannot be set because it is inherited from the enclosing component",
              TypeNames.rawTypeName(TypeName.get(requirement.type())))
          .build();
    }

    private ParameterSpec parameter(MethodSpec method) {
      return getOnlyElement(method.parameters);
    }

    private MethodSpec maybeReturnThis(MethodSpec.Builder method) {
      MethodSpec built = method.build();
      return built.returnType.equals(TypeName.VOID)
          ? built
          : method.addStatement("return this").build();
    }

    private void addFactoryMethod() {
      if (!componentImplementation.isAbstract()) {
        classBuilder.addMethod(factoryMethod());
      }
    }

    MethodSpec factoryMethod() {
      MethodSpec.Builder factoryMethod = factoryMethodBuilder();
      factoryMethod.returns(ClassName.get(graph().componentTypeElement())).addModifiers(PUBLIC);

      providedRequirements().forEach(
          requirement -> {
            FieldSpec field = fields.get(requirement);
            switch (requirement.nullPolicy(elements, types)) {
              case NEW:
                checkState(requirement.kind().isModule());
                factoryMethod
                    .beginControlFlow("if ($N == null)", field)
                    .addStatement(
                        "this.$N = $L",
                        field,
                        newModuleInstance(
                            requirement.typeElement(), componentImplementation.name(), elements))
                    .endControlFlow();
                break;
              case THROW:
                // TODO(cgdecker,ronshapiro): ideally this should use the key instead of a class for
                // @BindsInstance requirements, but that's not easily proguardable.
                factoryMethod.addStatement(
                    "$T.checkBuilderRequirement($N, $T.class)",
                    Preconditions.class,
                    field,
                    TypeNames.rawTypeName(field.type));
                break;
              case ALLOW:
                break;
            }
          });
      factoryMethod.addStatement(
          "return new $T($L)", componentImplementation.name(), componentConstructorArgs());
      return factoryMethod.build();
    }

    /** Returns a builder for the creator's factory method. */
    protected abstract MethodSpec.Builder factoryMethodBuilder();

    private CodeBlock componentConstructorArgs() {
      return providedRequirements().stream()
          .map(fields::get)
          .map(field -> CodeBlock.of("$N", field))
          .collect(toParametersCodeBlock());
    }
  }

  /** Builder for a creator type defined by a {@code ComponentCreatorDescriptor}. */
  private final class BuilderForCreatorDescriptor extends Builder {
    final ComponentCreatorDescriptor creatorDescriptor;

    BuilderForCreatorDescriptor(ComponentImplementation componentImplementation) {
      super(componentImplementation);
      this.creatorDescriptor =
          componentImplementation.componentDescriptor().creatorDescriptor().get();
    }

    @Override
    protected ImmutableMap<ComponentRequirement, RequirementStatus> settableRequirements() {
      return Maps.toMap(creatorDescriptor.settableRequirements(), this::requirementStatus);
    }

    @Override
    protected Modifier visibility() {
      if (componentImplementation.isAbstract()) {
        // The component creator class of a top-level component implementation in ahead-of-time
        // subcomponents mode must be public, not protected, because the creator's subclass will
        // be a sibling of the component subclass implementation, not nested.
        return componentImplementation.isNested() ? PROTECTED : PUBLIC;
      }
      return PRIVATE;
    }

    @Override
    protected void setSupertype() {
      if (componentImplementation.baseImplementation().isPresent()) {
        // If there's a superclass, extend the creator defined there.
        classBuilder.superclass(
            componentImplementation.baseImplementation().get().getCreatorName());
      } else {
        addSupertype(classBuilder, creatorDescriptor.typeElement());
      }
    }

    @Override
    protected void addConstructor() {
      // Just use the implicit no-arg public constructor.
    }

    @Override
    protected MethodSpec.Builder factoryMethodBuilder() {
      ExecutableElement factoryMethodElement = creatorDescriptor.factoryMethod();
      // Note: we don't use the factoryMethodElement.getReturnType() as the return type
      // because it might be a type variable.  We make use of covariant returns to allow
      // us to return the component type, which will always be valid.
      return methodBuilder(factoryMethodElement.getSimpleName().toString())
          .addAnnotation(Override.class);
    }

    private RequirementStatus requirementStatus(ComponentRequirement requirement) {
      // In ahead-of-time subcomponents mode, all builder methods are defined at the base
      // implementation. The only case where a method needs to be overridden is for a repeated
      // module, which is unknown at the point when a base implementation is generated. We do this
      // at the root for simplicity (and as an aside, repeated modules are never used in google
      // as of 11/28/18, and thus the additional cost of including these methods at the root is
      // negligible).
      if (isRepeatedModule(requirement)) {
        return RequirementStatus.UNSETTABLE_REPEATED_MODULE;
      }

      if (hasBaseCreatorImplementation()) {
        return RequirementStatus.IMPLEMENTED_IN_SUPERTYPE;
      }

      return componentImplementation.requirements().contains(requirement)
          ? RequirementStatus.NEEDED
          : RequirementStatus.UNNEEDED;
    }

    /**
     * Returns whether the given requirement is for a repeat of a module inherited from an ancestor
     * component. This creator is not allowed to set such a module.
     */
    final boolean isRepeatedModule(ComponentRequirement requirement) {
      return !componentImplementation.requirements().contains(requirement)
          && !isOwnedModule(requirement);
    }

    /**
     * Returns whether the given {@code requirement} is for a module type owned by the component.
     */
    private boolean isOwnedModule(ComponentRequirement requirement) {
      return graph().ownedModuleTypes().contains(requirement.typeElement());
    }

    private boolean hasBaseCreatorImplementation() {
      return !componentImplementation.isAbstract()
          && componentImplementation.baseImplementation().isPresent();
    }

    @Override
    protected MethodSpec.Builder setterMethodBuilder(ComponentRequirement requirement) {
      ExecutableElement supertypeMethod = creatorDescriptor.elementForRequirement(requirement);
      MethodSpec.Builder method =
          MethodSpec.overriding(
              supertypeMethod, asDeclared(creatorDescriptor.typeElement().asType()), types);
      if (!supertypeMethod.getReturnType().getKind().equals(TypeKind.VOID)) {
        // Take advantage of covariant returns so that we don't have to worry about setter methods
        // that return type variables.
        method.returns(className);
      }
      return method;
    }
  }

  /**
   * Builder for a component builder class that is automatically generated for a root component that
   * does not have its own user-defined creator type (i.e. a {@code ComponentCreatorDescriptor}).
   */
  private final class BuilderForGeneratedRootComponentBuilder extends Builder {
    BuilderForGeneratedRootComponentBuilder(ComponentImplementation componentImplementation) {
      super(componentImplementation);
    }

    @Override
    protected ImmutableMap<ComponentRequirement, RequirementStatus> settableRequirements() {
      return Maps.toMap(
          graph().componentDescriptor().dependenciesAndConcreteModules(),
          requirement -> componentImplementation.requirements().contains(requirement)
              ? RequirementStatus.NEEDED
              : RequirementStatus.UNNEEDED);
    }

    @Override
    protected Modifier visibility() {
      return PUBLIC;
    }

    @Override
    protected void setSupertype() {
      // There's never a supertype for a root component auto-generated builder type.
    }

    @Override
    protected void addConstructor() {
      classBuilder.addMethod(constructorBuilder().addModifiers(PRIVATE).build());
    }

    @Override
    protected MethodSpec.Builder factoryMethodBuilder() {
      return methodBuilder("build");
    }

    @Override
    protected MethodSpec.Builder setterMethodBuilder(ComponentRequirement requirement) {
      String name = simpleVariableName(requirement.typeElement());
      return methodBuilder(name)
          .addModifiers(PUBLIC)
          .addParameter(TypeName.get(requirement.type()), name)
          .returns(className);
    }
  }

  /** Enumeration of statuses a component requirement may have in a creator. */
  enum RequirementStatus {
    /** An instance is needed to create the component. */
    NEEDED,

    /**
     * An instance is not needed to create the component, but the requirement is for a module owned
     * by the component. Setting the requirement is a no-op and any setter method should be marked
     * deprecated on the generated type as a warning to the user.
     */
    UNNEEDED,

    /**
     * The requirement may not be set in this creator because the module it is for is already
     * inherited from an ancestor component. Any setter method for it should throw an exception.
     */
    UNSETTABLE_REPEATED_MODULE,

    /**
     * The requirement is settable by the creator, but the setter method implementation already
     * exists in a supertype.
     */
    IMPLEMENTED_IN_SUPERTYPE,
    ;
  }
}
