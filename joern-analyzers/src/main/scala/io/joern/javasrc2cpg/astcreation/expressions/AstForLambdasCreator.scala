/*
 * Copyright 2025 The Joern Project
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
/*
 * Modifications copyright 2025 Brokk, Inc. and made available under the GPLv3.
 *
 * The original file can be found at https://github.com/joernio/joern/blob/3e923e15368e64648e6c5693ac014a2cac83990a/joern-cli/frontends/javasrc2cpg/src/main/scala/io/joern/javasrc2cpg/astcreation/expressions/AstForLambdasCreator.scala
 */
package io.joern.javasrc2cpg.astcreation.expressions

import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.stmt.{BlockStmt, Statement}
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap
import com.github.javaparser.resolution.types.{ResolvedReferenceType, ResolvedType, ResolvedTypeVariable}
import io.joern.javasrc2cpg.astcreation.expressions.AstForLambdasCreator.{
  ClosureBindingEntry,
  LambdaBody,
  LambdaImplementedInfo
}
import io.joern.javasrc2cpg.astcreation.{AstCreator, ExpectedType, getAstParentInfo}
import io.joern.javasrc2cpg.scope.Scope.ScopeVariable
import io.joern.javasrc2cpg.typesolvers.TypeInfoCalculator.{ObjectMethodSignatures, TypeConstants}
import io.joern.javasrc2cpg.util.BindingTable.createBindingTable
import io.joern.javasrc2cpg.util.Util.{composeMethodFullName, composeMethodLikeSignature, composeUnresolvedSignature}
import io.joern.javasrc2cpg.util.{BindingTable, BindingTableAdapterForLambdas, LambdaBindingInfo, NameConstants}
import io.joern.x2cpg.AstNodeBuilder.{bindingNode, closureBindingNode}
import io.joern.x2cpg.utils.AstPropertiesUtil.*
import io.joern.x2cpg.Ast
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.nodes.MethodParameterIn.PropertyDefaults as ParameterDefaults
import io.shiftleft.codepropertygraph.generated.{EdgeTypes, EvaluationStrategies, ModifierTypes}
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.RichOptional
import scala.util.{Failure, Success, Try}

object AstForLambdasCreator {
  case class LambdaImplementedInfo(
    implementedInterface: Option[ResolvedReferenceType],
    implementedMethod: Option[ResolvedMethodDeclaration]
  )

  case class ClosureBindingEntry(node: ScopeVariable, binding: NewClosureBinding)

  case class LambdaBody(body: Ast, capturedVariables: Seq[ClosureBindingEntry]) {
    def nodes: Seq[NewNode] = body.nodes.toSeq
  }

}

private[expressions] trait AstForLambdasCreator { this: AstCreator =>

  private val logger = LoggerFactory.getLogger(this.getClass)

  private def createAndPushLambdaMethod(
    expr: LambdaExpr,
    lambdaMethodName: String,
    implementedInfo: LambdaImplementedInfo,
    variablesInScope: Seq[ScopeVariable],
    expectedLambdaType: ExpectedType
  ): (NewMethod, LambdaBody) = {

    val implementedMethod    = implementedInfo.implementedMethod
    val implementedInterface = implementedInfo.implementedInterface

    // We need to get this information from the expected type as the JavaParser
    // symbol solver returns the erased types when resolving the lambda itself.
    val expectedTypeParamTypes = genericParamTypeMapForLambda(expectedLambdaType)
    val parametersWithoutThis  = buildParamListForLambda(expr, implementedMethod, expectedTypeParamTypes)
    val returnType             = getLambdaReturnType(implementedInterface, implementedMethod, expectedTypeParamTypes)

    val lambdaMethodNode = createLambdaMethodNode(expr, lambdaMethodName, parametersWithoutThis, returnType)

    val (astParentType, astParentFullName) = scope.getAstParentInfo(prioritizeMethodAstParent = true)
    lambdaMethodNode
      .astParentType(astParentType)
      .astParentFullName(astParentFullName)

    // TODO: lambda method scope can be static if no non-static captures are used
    scope.pushMethodScope(lambdaMethodNode, expectedLambdaType, isStatic = false)

    val lambdaBody = astForLambdaBody(expr, lambdaMethodName, expr.getBody, variablesInScope, returnType)

    val thisParam = lambdaBody.nodes
      .collect { case identifier: NewIdentifier => identifier }
      .find { identifier => identifier.name == NameConstants.This || identifier.name == NameConstants.Super }
      .map { _ =>
        val typeFullName = scope.enclosingTypeDecl.fullName
        Ast(thisNodeForMethod(expr, typeFullName))
      }
      .toList

    val parameters = thisParam ++ parametersWithoutThis

    val lambdaParameterNamesToNodes =
      parameters
        .flatMap(_.root)
        .collect { case param: NewMethodParameterIn => param }
        .map { param => param.name -> param }
        .toMap

    val identifiersMatchingParams = lambdaBody.nodes
      .collect { case identifier: NewIdentifier => identifier }
      .filter { identifier => lambdaParameterNamesToNodes.contains(identifier.name) }

    val returnNode      = methodReturnNode(expr, returnType.getOrElse(defaultTypeFallback()))
    val virtualModifier = Some(modifierNode(expr, ModifierTypes.VIRTUAL))
    val staticModifier  = Option.when(thisParam.isEmpty)(modifierNode(expr, ModifierTypes.STATIC))
    val privateModifier = Some(modifierNode(expr, ModifierTypes.PRIVATE))
    val lambdaModifier  = Some(modifierNode(expr, ModifierTypes.LAMBDA))

    val modifiers = List(virtualModifier, staticModifier, privateModifier, lambdaModifier).flatten.map(Ast(_))

    val lambdaMethodAstWithoutRefs =
      Ast(lambdaMethodNode)
        .withChildren(parameters)
        .withChild(lambdaBody.body)
        .withChild(Ast(returnNode))
        .withChildren(modifiers)

    val lambdaMethodAst = identifiersMatchingParams.foldLeft(lambdaMethodAstWithoutRefs)((ast, identifier) =>
      ast.withRefEdge(identifier, lambdaParameterNamesToNodes(identifier.name))
    )

    scope.popMethodScope()
    Ast.storeInDiffGraph(lambdaMethodAst, diffGraph)

    lambdaMethodNode -> lambdaBody
  }

  private def lambdaMethodSignature(returnType: Option[String], parameters: Seq[Ast]): String = {
    val maybeParameterTypes = toOptionList(parameters.map(_.rootType))
    val containsEmptyType   = maybeParameterTypes.exists(_.contains(ParameterDefaults.TypeFullName))

    (returnType, maybeParameterTypes) match {
      case (Some(returnType), Some(parameterTypes)) if !containsEmptyType =>
        composeMethodLikeSignature(returnType, parameterTypes)

      case _ => composeUnresolvedSignature(parameters.size)
    }
  }

  private def createLambdaMethodNode(
    lambdaExpr: LambdaExpr,
    lambdaName: String,
    parameters: Seq[Ast],
    returnType: Option[String]
  ): NewMethod = {
    val signature              = lambdaMethodSignature(returnType, parameters)
    val (_, astParentFullName) = scope.getAstParentInfo(prioritizeMethodAstParent = true)
    // Composing based on a method full name without signature will be fine as the lambda
    // counter is at a file-level
    val surroundingFullName = scope.enclosingMethod
      .map(x => astParentFullName.stripSuffix(s":${x.method.signature}"))
      .getOrElse(astParentFullName)
    val lambdaFullName = composeMethodFullName(surroundingFullName, lambdaName, signature)

    val genericSignature = binarySignatureCalculator.lambdaMethodBinarySignature(lambdaExpr)
    methodNode(
      lambdaExpr,
      lambdaName,
      "<lambda>",
      lambdaFullName,
      Some(signature),
      filename,
      genericSignature = Option(genericSignature)
    )
  }

  private def createAndPushLambdaTypeDecl(
    lambdaMethodNode: NewMethod,
    implementedInfo: LambdaImplementedInfo
  ): NewTypeDecl = {
    val inheritsFromTypeFullName =
      implementedInfo.implementedInterface
        .flatMap(typeInfoCalc.fullName)
        .orElse(Some(TypeConstants.Object))
        .toList

    typeInfoCalc.registerType(lambdaMethodNode.fullName)
    val lambdaTypeDeclNode =
      NewTypeDecl()
        .fullName(lambdaMethodNode.fullName)
        .name(lambdaMethodNode.name)
        .inheritsFromTypeFullName(inheritsFromTypeFullName)
        .genericSignature(binarySignatureCalculator.unspecifiedClassType)
        .isExternal(false)
    scope.addLocalDecl(Ast(lambdaTypeDeclNode))

    lambdaTypeDeclNode
  }

  private def getLambdaImplementedInfo(expr: LambdaExpr, expectedType: ExpectedType): LambdaImplementedInfo = {
    val maybeImplementedType = {
      val maybeResolved = tryWithSafeStackOverflow(expr.calculateResolvedType())
      maybeResolved.toOption
        .orElse(expectedType.resolvedType)
        .collect { case refType: ResolvedReferenceType => refType }
    }

    val maybeImplementedInterface = maybeImplementedType.flatMap(_.getTypeDeclaration.toScala)

    if (maybeImplementedInterface.isEmpty) {
      val location = s"$filename:${line(expr)}:${column(expr)}"
      logger.debug(
        s"Could not resolve the interface implemented by a lambda. Type info may be missing: $location. Type info may be missing."
      )
    }

    val maybeBoundMethod = maybeImplementedInterface.flatMap { interface =>
      interface.getDeclaredMethods.asScala
        .filter(_.isAbstract)
        .filterNot { method =>
          // Filter out java.lang.Object methods re-declared by the interface as these are also considered abstract.
          // See https://docs.oracle.com/javase/8/docs/api/java/lang/FunctionalInterface.html for details.
          Try(method.getSignature) match {
            case Success(signature) => ObjectMethodSignatures.contains(signature)
            case Failure(_) =>
              false // If the signature could not be calculated, it's probably not a standard object method.
          }
        }
        .headOption
    }

    LambdaImplementedInfo(maybeImplementedType, maybeBoundMethod)
  }

  private def addClosureBindingsToDiffGraph(
    bindingEntries: Iterable[ClosureBindingEntry],
    methodRef: NewMethodRef
  ): Unit = {
    bindingEntries.foreach { case ClosureBindingEntry(nodeTypeInfo, closureBinding) =>
      diffGraph.addNode(closureBinding)
      diffGraph.addEdge(closureBinding, nodeTypeInfo.node, EdgeTypes.REF)
      diffGraph.addEdge(methodRef, closureBinding, EdgeTypes.CAPTURE)
    }
  }

  def astForLambdaExpr(expr: LambdaExpr, expectedType: ExpectedType): Ast = {

    val lambdaMethodName = nextClosureName()

    val variablesInScope = scope.variablesInScope

    val implementedInfo = getLambdaImplementedInfo(expr, expectedType)
    val (lambdaMethodNode, lambdaBody) =
      createAndPushLambdaMethod(expr, lambdaMethodName, implementedInfo, variablesInScope, expectedType)

    val methodRef =
      NewMethodRef()
        .methodFullName(lambdaMethodNode.fullName)
        .typeFullName(lambdaMethodNode.fullName)
        .code(lambdaMethodNode.fullName)

    addClosureBindingsToDiffGraph(lambdaBody.capturedVariables, methodRef)

    val interfaceBinding = implementedInfo.implementedMethod.map { implementedMethod =>
      bindingNode(implementedMethod.getName, lambdaMethodNode.signature, lambdaMethodNode.fullName)
    }

    val bindingTable = getLambdaBindingTable(
      LambdaBindingInfo(lambdaMethodNode.fullName, implementedInfo.implementedInterface, interfaceBinding)
    )

    val lambdaTypeDeclNode = createAndPushLambdaTypeDecl(lambdaMethodNode, implementedInfo)
    BindingTable.createBindingNodes(diffGraph, lambdaTypeDeclNode, bindingTable)

    Ast(methodRef)
  }

  private def getLambdaBindingTable(lambdaBindingInfo: LambdaBindingInfo): BindingTable = {
    val fullName = lambdaBindingInfo.fullName

    bindingTableCache.getOrElseUpdate(
      fullName,
      createBindingTable(
        fullName,
        lambdaBindingInfo,
        getBindingTable,
        new BindingTableAdapterForLambdas(methodSignature)
      )
    )
  }

  private def defineCapturedVariables(
    lambdaNode: LambdaExpr,
    lambdaMethodName: String,
    capturedVariables: Seq[ScopeVariable]
  ): Seq[(ClosureBindingEntry, NewLocal)] = {
    capturedVariables
      .groupBy(_.name)
      .map { case (name, variables) =>
        val closureBindingId = s"$filename:$lambdaMethodName:$name"
        val closureBinding   = closureBindingNode(closureBindingId, name, EvaluationStrategies.BY_SHARING)

        val scopeVariable = variables.head
        val capturedLocal = localNode(
          lambdaNode,
          scopeVariable.mangledName,
          scopeVariable.mangledName,
          scopeVariable.typeFullName,
          Option(closureBindingId),
          Option(scopeVariable.genericSignature)
        )
        scope.enclosingBlock.foreach(_.addLocal(capturedLocal, scopeVariable.name))

        ClosureBindingEntry(scopeVariable, closureBinding) -> capturedLocal
      }
      .toSeq
  }

  private def astForLambdaBody(
    lambdaExpr: LambdaExpr,
    lambdaMethodName: String,
    body: Statement,
    variablesInScope: Seq[ScopeVariable],
    returnType: Option[String]
  ): LambdaBody = {
    val outerScopeVariableNames = variablesInScope.map(x => x.name -> x).toMap

    val stmts = body match {
      case block: BlockStmt =>
        scope.pushBlockScope()
        val stmts = block.getStatements.asScala.flatMap(astsForStatement).toSeq
        scope.popBlockScope()
        stmts
      case stmt if returnType.contains(TypeConstants.Void) => astsForStatement(stmt)
      case stmt =>
        val retNode    = returnNode(stmt, s"return ${body.toString}")
        val returnArgs = astsForStatement(stmt)
        Seq(returnAst(retNode, returnArgs))
    }

    val capturedVariables =
      stmts.flatMap(_.nodes).collect {
        case i: NewIdentifier if outerScopeVariableNames.contains(i.name) => outerScopeVariableNames(i.name)
      }
    val bindingsToLocals      = defineCapturedVariables(lambdaExpr, lambdaMethodName, capturedVariables)
    val capturedLocalAsts     = bindingsToLocals.map(_._2).map(Ast(_))
    val closureBindingEntries = bindingsToLocals.map(_._1)
    val temporaryLocalAsts = scope.enclosingMethod.map(_.getAndClearUnaddedPatternLocals()).getOrElse(Nil).map(Ast(_))

    val blockAst = Ast(blockNode(body))
      .withChildren(temporaryLocalAsts)
      .withChildren(capturedLocalAsts)
      .withChildren(stmts)
    LambdaBody(blockAst, closureBindingEntries)
  }

  private def genericParamTypeMapForLambda(expectedType: ExpectedType): ResolvedTypeParametersMap = {
    expectedType.resolvedType
      // This should always be true for correct code
      .collect { case r: ResolvedReferenceType => r }
      .map(_.typeParametersMap())
      .getOrElse(new ResolvedTypeParametersMap.Builder().build())
  }

  private def buildParamListForLambda(
    expr: LambdaExpr,
    maybeBoundMethod: Option[ResolvedMethodDeclaration],
    expectedTypeParamTypes: ResolvedTypeParametersMap
  ): Seq[Ast] = {
    val lambdaParameters = expr.getParameters.asScala.toList
    val paramTypesList = maybeBoundMethod match {
      case Some(resolvedMethod) =>
        val resolvedParameters = (0 until resolvedMethod.getNumberOfParams).map(resolvedMethod.getParam)

        // Substitute generic typeParam with the expected type if it can be found; leave unchanged otherwise.
        resolvedParameters.map(param => Try(param.getType)).map {
          case Success(resolvedType: ResolvedTypeVariable) =>
            val typ = expectedTypeParamTypes.getValue(resolvedType.asTypeParameter)
            typeInfoCalc.fullName(typ)

          case Success(resolvedType) => typeInfoCalc.fullName(resolvedType)

          case Failure(_) => None
        }

      case None =>
        // Unless types are explicitly specified in the lambda definition,
        // this will yield the erased types which is why the actual lambda
        // expression parameters are only used as a fallback.
        lambdaParameters
          .flatMap(param => tryWithSafeStackOverflow(typeInfoCalc.fullName(param.getType)).toOption)
    }

    if (paramTypesList.sizeIs != lambdaParameters.size) {
      logger.debug(s"Found different number lambda params and param types for $expr. Some parameters will be missing.")
    }

    val parameterNodes = lambdaParameters
      .zip(paramTypesList)
      .zipWithIndex
      .map { case ((param, maybeType), idx) =>
        val name         = param.getNameAsString
        val typeFullName = maybeType.getOrElse(defaultTypeFallback())
        val code         = s"$typeFullName $name"
        val evalStrat =
          if (tryWithSafeStackOverflow(param.getType).toOption.exists(_.isPrimitiveType)) EvaluationStrategies.BY_VALUE
          else EvaluationStrategies.BY_SHARING
        val paramNode = NewMethodParameterIn()
          .name(name)
          .index(idx + 1)
          .order(idx + 1)
          .code(code)
          .evaluationStrategy(evalStrat)
          .typeFullName(typeFullName)
          .lineNumber(line(expr))
          .columnNumber(column(expr))
        typeInfoCalc.registerType(typeFullName)
        paramNode
      }

    parameterNodes.foreach { paramNode =>
      scope.enclosingMethod.get
        .addParameter(paramNode, binarySignatureCalculator.unspecifiedClassType)
    }

    parameterNodes.map(Ast(_))
  }

  private def getLambdaReturnType(
    maybeResolvedLambdaType: Option[ResolvedType],
    maybeBoundMethod: Option[ResolvedMethodDeclaration],
    expectedTypeParamTypes: ResolvedTypeParametersMap
  ): Option[String] = {
    val maybeBoundMethodReturnType = maybeBoundMethod.flatMap { boundMethod =>
      Try(boundMethod.getReturnType).collect {
        case returnType: ResolvedTypeVariable => expectedTypeParamTypes.getValue(returnType.asTypeParameter)
        case other                            => other
      }.toOption
    }

    val returnType = maybeBoundMethodReturnType.orElse(maybeResolvedLambdaType)
    returnType.flatMap(typeInfoCalc.fullName)
  }
}
