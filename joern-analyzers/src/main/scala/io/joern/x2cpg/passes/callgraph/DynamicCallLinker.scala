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
 * The original file can be found at https://github.com/joernio/joern/blob/3e923e15368e64648e6c5693ac014a2cac83990a/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/passes/callgraph/DynamicCallLinker.scala
 */
package io.joern.x2cpg.passes.callgraph

import io.joern.x2cpg.Defines.DynamicCallUnknownFullName
import io.shiftleft.codepropertygraph.generated.nodes.{Call, Method, StoredNode, TypeDecl}
import io.shiftleft.codepropertygraph.generated.{Cpg, DispatchTypes, EdgeTypes, PropertyNames}
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable

class DynamicCallLinker(cpg: Cpg) extends CpgPass(cpg) {

  import DynamicCallLinker.*

  // Used to track potential method candidates for a given method fullname. Since our method full names contain the type
  // decl we don't need to specify an addition map to wrap this in. LinkedHashSets are used here to preserve order in
  // the best interest of reproducibility during debugging.
  private val validM = mutable.Map.empty[String, mutable.LinkedHashSet[String]]
  // Used for O(1) lookups on methods that will work without indexManager
  private val typeMap = mutable.Map.empty[String, TypeDecl]
  // For linking loose method stubs that cannot be resolved by crawling parent types
  private val methodMap = mutable.Map.empty[String, Method]

  private def initMaps(): Unit = {
    cpg.typeDecl.foreach { typeDecl =>
      typeMap += (typeDecl.fullName -> typeDecl)
    }
    cpg.method
      .filter(m => !m.name.startsWith("<operator>"))
      .foreach { method => methodMap += (method.fullName -> method) }
  }

  /** Main method of enhancement - to be implemented by child class
    */
  override def run(dstGraph: DiffGraphBuilder): Unit = {
    // Perform early stopping in the case of no virtual calls
    if (!cpg.call.exists(_.dispatchType == DispatchTypes.DYNAMIC_DISPATCH)) {
      return
    }
    initMaps()
    // ValidM maps class C and method name N to the set of
    // func ptrs implementing N for C and its subclasses
    for {
      typeDecl <- cpg.typeDecl
      method   <- typeDecl.method
    } {
      val methodName = method.fullName
      val candidates =
        (typeDecl :: typeDecl.derivedTypeDeclTransitive.l).fullName.flatMap(staticLookup(_, method)).toSeq
      validM.put(methodName, mutable.LinkedHashSet.from(candidates))
    }

    cpg.call.filter(_.dispatchType == DispatchTypes.DYNAMIC_DISPATCH).foreach { call =>
      try {
        linkDynamicCall(call, dstGraph)
      } catch {
        case exception: Exception =>
          throw new RuntimeException(exception)
      }
    }
  }

  /** Returns the method from a sub-class implementing a method for the given subclass.
    */
  private def staticLookup(subclass: String, method: Method): Option[String] = {
    typeMap.get(subclass) match {
      case Some(sc) =>
        sc.method
          .nameExact(method.name)
          .and(_.signatureExact(method.signature))
          .map(_.fullName)
          .headOption
      case None => None
    }
  }

  private def resolveCallInSuperClasses(call: Call): Boolean = {
    def registerEntry(caller: String, callees: Seq[String]): Unit = {
      val entries =
        validM.getOrElse(call.methodFullName, mutable.LinkedHashSet.empty) ++ mutable.LinkedHashSet.from(callees)
      validM.put(caller, entries)
    }

    val inheritedMethodsWithMatchingName =
      call.receiver.typ.derivedTypeTransitive.referencedTypeDecl.method
        .nameExact(call.name)
        .l

    val inheritedMethodsWithMatchingSignatures =
      inheritedMethodsWithMatchingName.filter {
        case m if m.parameter.where(_.isVariadic).hasNext =>
          // next, we would want to check types, but this should be fine for now
          true
        case m => m.parameter.size == call.argument.size
      }

    if inheritedMethodsWithMatchingSignatures.nonEmpty then {
      // First the most precise
      registerEntry(call.methodFullName, inheritedMethodsWithMatchingSignatures.fullName.toSeq)
      true
    } else if (inheritedMethodsWithMatchingName.nonEmpty) {
      // Since some frontends may not have great signature support for calls sites, fall back to just names
      registerEntry(call.methodFullName, inheritedMethodsWithMatchingName.fullName.toSeq)
      true
    } else {
      false
    }
  }

  private def linkDynamicCall(call: Call, dstGraph: DiffGraphBuilder): Unit = {
    // This call linker requires a method full name entry
    if (call.methodFullName.equals("<empty>") || call.methodFullName.equals(DynamicCallUnknownFullName)) return
    // Support for overriding
    resolveCallInSuperClasses(call)

    validM.get(call.methodFullName) match {
      case Some(tgts) =>
        val callsOut = call.callee(NoResolve).fullName.toSetImmutable
        val tgtMs    = tgts.flatMap(destMethod => methodFullNameToNode(destMethod)).toSet
        // Non-overridden methods linked as external stubs should be excluded if they are detected
        val (externalMs, internalMs) = tgtMs.partition(_.isExternal)
        val callees                  = if (externalMs.nonEmpty && internalMs.nonEmpty) internalMs else tgtMs

        callees
          .filterNot(tgtM => callsOut.contains(tgtM.fullName))
          .foreach(dstGraph.addEdge(call, _, EdgeTypes.CALL))
      case None =>
        fallbackToStaticResolution(call, dstGraph)
    }
  }

  /** In the case where the method isn't an internal method and cannot be resolved by crawling TYPE_DECL nodes it can be
    * resolved from the map of external methods.
    */
  private def fallbackToStaticResolution(call: Call, dstGraph: DiffGraphBuilder): Unit = {
    methodMap.get(call.methodFullName) match {
      case Some(tgtM) if !tgtM.callIn(NoResolve).contains(call) => dstGraph.addEdge(call, tgtM, EdgeTypes.CALL)
      case None                                                 => printLinkingError(call)
      case _                                                    => // ignore
    }
  }

  private def nodesWithFullName(x: String): Iterator[StoredNode] =
    cpg.graph.nodesWithProperty(PropertyNames.FULL_NAME, x).cast[StoredNode]

  private def methodFullNameToNode(x: String): Option[Method] =
    nodesWithFullName(x).collectFirst { case x: Method => x }

  @inline
  private def printLinkingError(call: Call): Unit = {
    logger.info(
      s"Unable to link dynamic CALL with METHOD_FULL_NAME ${call.methodFullName} and context: " +
        s"${call.code} @ line ${call.lineNumber}"
    )
  }
}

object DynamicCallLinker {
  private val logger: Logger = LoggerFactory.getLogger(classOf[DynamicCallLinker])
}
