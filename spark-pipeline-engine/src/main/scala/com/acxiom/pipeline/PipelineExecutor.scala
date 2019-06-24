package com.acxiom.pipeline

import com.acxiom.pipeline.utils.ReflectionUtils
import org.apache.log4j.Logger

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

object PipelineExecutor {
  private val logger = Logger.getLogger(getClass)

  def executePipelines(pipelines: List[Pipeline],
                       initialPipelineId: Option[String],
                       initialContext: PipelineContext): PipelineExecutionResult = {
    val executingPipelines = if (initialPipelineId.isDefined) {
      pipelines.slice(pipelines.indexWhere(pipeline => {
        pipeline.id.get == initialPipelineId.getOrElse("")
      }), pipelines.length)
    } else {
      pipelines
    }
    val esContext = handleEvent(initialContext, "executionStarted", List(executingPipelines, initialContext))
    try {
      val pipelineLookup = executingPipelines.map(p => p.id.getOrElse("") -> p.name.getOrElse("")).toMap
      val ctx = executingPipelines.foldLeft(esContext)((accCtx, pipeline) => {
        val psCtx = handleEvent(accCtx, "pipelineStarted", List(pipeline, accCtx))
        // Map the steps for easier lookup during execution
        val stepLookup = pipeline.steps.get.map(step => {
          validateStep(step, pipeline)
          step.id.get -> step
        }).toMap
        // Set the pipelineId in the global lookup
        val updatedCtx = psCtx
          .setGlobal("pipelineId", pipeline.id)
          .setGlobal("stepId", pipeline.steps.get.head.id.get)
        try {
          val resultPipelineContext = executeStep(pipeline.steps.get.head, pipeline, stepLookup, updatedCtx)
          val messages = resultPipelineContext.getStepMessages
          processStepMessages(messages, pipelineLookup)
          handleEvent(resultPipelineContext, "pipelineFinished", List(pipeline, resultPipelineContext))
        } catch {
          case t: Throwable => throw handleStepExecutionExceptions(t, pipeline, accCtx, executingPipelines)
        }
      })
      PipelineExecutionResult(handleEvent(ctx, "executionFinished", List(executingPipelines, ctx)), success = true)
    } catch {
      case p: PauseException =>
        logger.info(s"Paused pipeline flow at pipeline ${p.pipelineId} step ${p.stepId}. ${p.message}")
        PipelineExecutionResult(esContext, success = false)
      case pse: PipelineStepException =>
        logger.error(s"Stopping pipeline because of an exception", pse)
        PipelineExecutionResult(esContext, success = false)
      case t: Throwable => throw t
    }
  }

  /**
    * This function will process step messages and throw any appropriate exceptions
    * @param messages A list of PipelineStepMessages that need to be processed.
    * @param pipelineLookup A map of Pipelines keyed by the id. This is used to quickly retrieve additional Pipeline data.
    */
  private def processStepMessages(messages: Option[List[PipelineStepMessage]], pipelineLookup: Map[String, String]): Unit = {
    if (messages.isDefined && messages.get.nonEmpty) {
      messages.get.foreach(m => m.messageType match {
        case PipelineStepMessageType.error =>
          throw PipelineException(message = Some(m.message), pipelineId = Some(m.pipelineId), stepId = Some(m.stepId))
        case PipelineStepMessageType.pause =>
          throw PauseException(message = Some(m.message), pipelineId = Some(m.pipelineId), stepId = Some(m.stepId))
        case PipelineStepMessageType.warn =>
          logger.warn(s"Step ${m.stepId} in pipeline ${pipelineLookup(m.pipelineId)} issued a warning: ${m.message}")
        case _ =>
      })
    }
  }

  @tailrec
  private def executeStep(step: PipelineStep, pipeline: Pipeline, steps: Map[String, PipelineStep],
                          pipelineContext: PipelineContext): PipelineContext = {
    logger.debug(s"Executing Step (${step.id.getOrElse("")}) ${step.displayName.getOrElse("")}")
    val ssContext = handleEvent(pipelineContext, "pipelineStepStarted", List(pipeline, step, pipelineContext))
    // Create a map of values for each defined parameter
    val parameterValues: Map[String, Any] = ssContext.parameterMapper.createStepParameterMap(step, ssContext)
    val result = step.executeIfEmpty.getOrElse("") match {
      // process step normally if empty
      case "" if step.`type`.getOrElse("") == "fork" => processForkStep(step, pipeline, steps, parameterValues, pipelineContext)
      case "" => ReflectionUtils.processStep(step, parameterValues, ssContext)
      case value: String =>
        logger.debug(s"Evaluating execute if empty: $value")
        // wrap the value in a parameter object
        val param = Parameter(Some("text"), Some("dynamic"), Some(true), None, Some(value))
        val ret = ssContext.parameterMapper.mapParameter(param, ssContext)
        ret match {
          case option: Option[Any] => if (option.isEmpty) {
            logger.debug("Executing step normally")
            ReflectionUtils.processStep(step, parameterValues, ssContext)
          } else {
            logger.debug("Returning existing value")
            PipelineStepResponse(option, None)
          }
          case _ =>
            logger.debug("Returning existing value")
            PipelineStepResponse(Some(ret), None)
        }
    }
    // setup the next step
    val nextStepId = getNextStepId(step, result)
    val newPipelineContext = updatePipelineContext(step, result, nextStepId, ssContext)
    // run the step finished event
    val sfContext = handleEvent(newPipelineContext, "pipelineStepFinished", List(pipeline, step, newPipelineContext))
    // Call the next step here
    if (steps.contains(nextStepId.getOrElse("")) && steps(nextStepId.getOrElse("")).`type`.getOrElse("") == "join") {
      sfContext
    } else if (steps.contains(nextStepId.getOrElse(""))) {
        executeStep(steps(nextStepId.get), pipeline, steps, sfContext)
    } else if (nextStepId.isDefined && nextStepId.get.nonEmpty) {
      throw PipelineException(message = Some("Step Id does not exist in pipeline"),
        pipelineId = Some(sfContext.getGlobalString("pipelineId").getOrElse("")), stepId = nextStepId)
    } else {
      sfContext
    }
  }

  @throws(classOf[PipelineException])
  private def validateStep(step: PipelineStep, pipeline: Pipeline): Unit = {
    if(step.id.getOrElse("") == ""){
      throw PipelineException(
        message = Some(s"Step is missing id in pipeline [${pipeline.id.get}]."),
        pipelineId = pipeline.id,
        stepId = step.id)
    }
    step.`type`.getOrElse("").toLowerCase match {
      case s if s == "pipeline" || s == "branch" =>
        if(step.engineMeta.isEmpty || step.engineMeta.get.spark.getOrElse("") != "") {
          throw PipelineException(
            message = Some(s"EngineMeta is required for [${step.`type`.get}] step [${step.id.get}] in pipeline [${pipeline.id.get}]"),
            pipelineId = pipeline.id,
            stepId = step.id)
        }
      case "fork" => validateForkStep(step, pipeline)
      case "join" =>
      case "" =>
        throw PipelineException(
          message = Some(s"[type] is required for step [${step.id.get}] in pipeline [${pipeline.id.get}]."),
          pipelineId = pipeline.id,
          stepId = step.id)
      case unknown => throw PipelineException(message = Some(s"Unknown pipeline type: [$unknown] for step [${step.id.get}] in pipeline [${pipeline.id.get}]."), pipelineId = pipeline.id, stepId = step.id)
    }
  }

  @throws(classOf[PipelineException])
  private def validateForkStep(step: PipelineStep, pipeline: Pipeline): Unit ={
    if(step.params.isEmpty) {
      throw PipelineException(
        message = Some(s"Parameters [forkByValues] and [forkMethod] is required for fork step [${step.id.get}] in pipeline [${pipeline.id.get}]."),
        pipelineId = pipeline.id,
        stepId = step.id)
    }
    val forkMethod = step.params.get.find(p => p.name.getOrElse("") == "forkMethod")
    if(forkMethod.isDefined && forkMethod.get.value.nonEmpty){
      val method = forkMethod.get.value.get.asInstanceOf[String]
      if(!(method == "serial" || method == "parallel")){
        throw PipelineException(
          message = Some(s"Unknown value [$method] for parameter [forkMethod]." +
          s" Value must be either [serial] or [parallel] for fork step [${step.id.get}] in pipeline [${pipeline.id.get}]."),
          pipelineId = pipeline.id,
          stepId = step.id)
      }
    } else {
      throw PipelineException(
        message = Some(s"Parameter [forkMethod] is required for fork step [${step.id.get}] in pipeline [${pipeline.id.get}]."),
        pipelineId = pipeline.id,
        stepId = step.id)
    }
    val forkByValues = step.params.get.find(p => p.name.getOrElse("") == "forkByValues")
    if(forkByValues.isEmpty || forkByValues.get.value.isEmpty){
      throw PipelineException(
        message = Some(s"Parameter [forkByValues] is required for fork step [${step.id.get}] in pipeline [${pipeline.id.get}]."),
        pipelineId = pipeline.id,
        stepId = step.id)
    }
  }

  private def updatePipelineContext(step: PipelineStep, result: Any, nextStepId: Option[String], pipelineContext: PipelineContext): PipelineContext = {
    step match {
      case PipelineStep(_, _, _, Some("fork"), _, _, _, _) => result.asInstanceOf[ForkStepResult].pipelineContext
      case _ =>
        pipelineContext.setParameterByPipelineId(pipelineContext.getGlobalString("pipelineId").getOrElse(""),
          step.id.getOrElse(""), result).setGlobal("stepId", nextStepId)
    }
  }

  private def getNextStepId(step: PipelineStep, result: Any): Option[String] = {
    step match {
      case PipelineStep(_, _, _, Some("branch"), _, _, _, _) =>
        // match the result against the step parameter name until we find a match
        val matchValue = result match {
          case response: PipelineStepResponse => response.primaryReturn.getOrElse("").toString
          case _ => result
        }
        val matchedParameter = step.params.get.find(p => p.name.get == matchValue.toString)
        // Use the value of the matched parameter as the next step id
        if (matchedParameter.isDefined) {
          Some(matchedParameter.get.value.get.asInstanceOf[String])
        } else {
          None
        }
      case PipelineStep(_, _, _, Some("fork"), _, _, _, _) => result.asInstanceOf[ForkStepResult].nextStepId
      case _ => step.nextStepId
    }
  }

  private def handleEvent(pipelineContext: PipelineContext, funcName: String, params: List[Any]): PipelineContext = {
    if (pipelineContext.pipelineListener.isDefined) {
      val rCtx = ReflectionUtils.executeFunctionByName(pipelineContext.pipelineListener.get, funcName, params).asInstanceOf[Option[PipelineContext]]
      if (rCtx.isEmpty) pipelineContext else rCtx.get
    } else { pipelineContext }
  }

  private def handleStepExecutionExceptions(t: Throwable, pipeline: Pipeline,
                                            pipelineContext: PipelineContext,
                                            pipelines: List[Pipeline]): PipelineStepException = {
    val ex = t match {
      case se: PipelineStepException => se
      case t: Throwable => PipelineException(message = Some("An unknown exception has occurred"), cause = t,
        pipelineId = pipeline.id, stepId = Some("Unknown"))
    }
    if (pipelineContext.pipelineListener.isDefined) {
      pipelineContext.pipelineListener.get.registerStepException(ex, pipelineContext)
      pipelineContext.pipelineListener.get.executionStopped(pipelines.slice(0, pipelines.indexWhere(pipeline => {
        pipeline.id.get == pipeline.id.getOrElse("")
      }) + 1), pipelineContext)
    }
    ex
  }

  /**
    * Special handling of fork steps.
    * @param step The fork step
    * @param pipeline The pipeline being executed
    * @param steps The step lookup
    * @param parameterValues The parameterValues for this step
    * @param pipelineContext The current pipeline context
    * @return The result of processing the forked steps.
    */
  private def processForkStep(step: PipelineStep,
                              pipeline: Pipeline,
                              steps: Map[String, PipelineStep],
                              parameterValues: Map[String, Any],
                              pipelineContext: PipelineContext): ForkStepResult = {
    // Get the first step
    val firstStep = steps(step.nextStepId.getOrElse(""))
    // Create the list of steps that need to be executed starting with the "nextStepId"
    val newSteps = getForkSteps(firstStep, pipeline, steps, List())
    // Identify the join steps and verify that only one is present
    val joinSteps = newSteps.filter(_.`type`.getOrElse("") == "join")
    val newStepLookup = newSteps.foldLeft(Map[String, PipelineStep]())((map, s) => map + (s.id.get -> s))
    // See if the forks should be executed in threads or a loop
    val forkByValues = parameterValues("forkByValues").asInstanceOf[List[Any]]
    val results = if (parameterValues("forkMethod").asInstanceOf[String] == "parallel") {
      processForkStepsParallel(forkByValues, firstStep, step.id.get, pipeline, newStepLookup, pipelineContext)
    } else { // "serial"
      processForkStepsSerial(forkByValues, firstStep, step.id.get, pipeline, newStepLookup, pipelineContext)
    }
    // Gather the results and create a list
    val finalResult = results.sortBy(_.index).foldLeft(ForkStepExecutionResult(-1, Some(pipelineContext), None))((combinedResult, result) => {
      if (result.result.isDefined) {
        val ctx = result.result.get
        mergeMessages(combinedResult.result.get, ctx.getStepMessages.get, result.index)
        combinedResult.copy(result = Some(mergeResponses(combinedResult.result.get, ctx, pipeline.id.getOrElse(""), newSteps, result.index)))
      } else if (result.error.isDefined) {
        if (combinedResult.error.isDefined) {
          combinedResult.copy(error = Some(combinedResult.error.get.asInstanceOf[ForkedPipelineStepException].addException(result.error.get, result.index)))
        } else {
          combinedResult.copy(error =
            Some(ForkedPipelineStepException(message = Some("One or more errors has occurred while processing fork step:\n"),
              exceptions = Map(result.index -> result.error.get))))
        }
      } else { // This should never happen
        combinedResult
      }
    })
    if (finalResult.error.isDefined) {
      throw finalResult.error.get
    } else {
      ForkStepResult(if (joinSteps.nonEmpty) {
        joinSteps.head.nextStepId
      } else { None }, finalResult.result.get)
    }
  }

  /**
    * Merges any messages into the provided PipelineContext. Each message will be converted to a ForkedPipelineStepMessage
    * to allow tracking of the execution id.
    *
    * @param pipelineContext The PipelineContext to merge the messages into
    * @param messages A list of messages to merge
    * @param executionId The execution id to attach to each message
    */
  private def mergeMessages(pipelineContext: PipelineContext, messages: List[PipelineStepMessage], executionId: Int): Unit = {
    messages.foreach(message =>
      pipelineContext.addStepMessage(ForkedPipelineStepMessage(message.message, message.stepId, message.pipelineId, message.messageType, Some(executionId)))
    )
  }

  /**
    * Iterates the list of fork steps merging the results into the provided PipelineContext. Results will be stored as
    * Options in a list. If this execution does not have a result, then None will be stored in it's place. Secondary
    * response maps fill have the values stored as a list as well.
    *
    * @param pipelineContext The context to write the results.
    * @param source The source context to retrieve the execution results
    * @param pipelineId The pipeline id that is used to run these steps.
    * @param forkSteps A list of steps that were used during the fork porcessing
    * @param executionId The execution id of this process. This will be used as a position for result storage in the list.
    * @return A PipelineContext with the merged results.
    */
  private def mergeResponses(pipelineContext: PipelineContext, source: PipelineContext, pipelineId: String,
                             forkSteps: List[PipelineStep], executionId: Int): PipelineContext = {
    val sourceParameter = source.parameters.getParametersByPipelineId(pipelineId)
    val sourceParameters = sourceParameter.get.parameters
    forkSteps.foldLeft(pipelineContext)((ctx, step) => {
      val rootParameter = ctx.parameters.getParametersByPipelineId(pipelineId)
      val parameters = if (rootParameter.isEmpty) {
        Map[String, Any]()
      } else {
        rootParameter.get.parameters
      }
      // Get the root step response
      val response = if (parameters.contains(step.id.getOrElse(""))) {
        val r = parameters(step.id.getOrElse("")).asInstanceOf[PipelineStepResponse]
        if (r.primaryReturn.isDefined && r.primaryReturn.get.isInstanceOf[List[_]]) {
          r
        } else {
          PipelineStepResponse(Some(List[Any]()), r.namedReturns)
        }
      } else {
        PipelineStepResponse(Some(List[Any]()), Some(Map[String, Any]()))
      }
      // Get the source response
      val updatedResponse = if (sourceParameters.contains(step.id.getOrElse(""))) {
        val r = sourceParameters(step.id.getOrElse(""))
        val stepResponse = r match {
          case a: PipelineStepResponse => a
          case option: Option[_] if option.isDefined && option.get.isInstanceOf[PipelineStepResponse] => option.get.asInstanceOf[PipelineStepResponse]
          case option: Option[_] if option.isDefined => PipelineStepResponse(option, None)
          case any => PipelineStepResponse(Some(any), None)
        }
        // Merge the primary response with the root
        val primaryList = response.primaryReturn.get.asInstanceOf[List[Option[_]]]
        // See if the list needs to be filled in
        val responseList = appendForkedResponseToList(primaryList, stepResponse.primaryReturn, executionId)
        val rootNamedReturns = response.namedReturns.getOrElse(Map[String, Any]())
        val sourceNamedReturns = stepResponse.namedReturns.getOrElse(Map[String, Any]())
        val mergedSecondaryReturns = mergeSecondaryReturns(rootNamedReturns, sourceNamedReturns, executionId)
        // Append this response to the list and update the PipelineStepResponse
        PipelineStepResponse(Some(responseList), Some(mergedSecondaryReturns))
      } else {
        response
      }
      ctx.setParameterByPipelineId(pipelineId, step.id.getOrElse(""), updatedResponse)
    })
  }

  /**
    * Appends the provided value to the list at the correct index based on the executionId.
    * @param list the list to append the value
    * @param executionId The execution id about to be appended
    * @return A list with any missing elements populated with None and the provided element appended.
    */
  private def appendForkedResponseToList(list: List[Option[_]], value: Option[Any], executionId: Int): List[Option[_]] = {
    val updateList = if (list.length < executionId) {
      list ::: List.fill(executionId - list.length)(None)
    } else {
      list
    }
    updateList :+ value
  }

  /**
    * Merges the values in the sourceNamedReturns into the elements in the rootNamedReturns
    * @param rootNamedReturns The base map to merge into
    * @param sourceNamedReturns The source map containing the values
    * @param executionId The executionId used for list positioning.
    * @return A map containing the values of the source merged into the root.
    */
  private def mergeSecondaryReturns(rootNamedReturns: Map[String, Any],
                                    sourceNamedReturns: Map[String, Any],
                                    executionId: Int): Map[String, Any] = {
    val keys = rootNamedReturns.keySet ++ sourceNamedReturns.keySet
    keys.foldLeft(rootNamedReturns)((map, key) => {
      map + (key -> appendForkedResponseToList(
        rootNamedReturns.getOrElse(key, List[Option[_]]()) match {
          case list: List[Option[_]] => list
          case option: Option[_] => List(option)
          case any => List(Some(any))
        },
        sourceNamedReturns.getOrElse(key, None) match {
          case option: Option[_] => option
          case any: Any => Some(any)
        }, executionId))
    })
  }

  /**
    * Processes a set of forked steps in serial. All values will be processed regardless of individual failures.
    * @param forkByValues The values to fork
    * @param firstStep The first step to process
    * @param forkStepId The id of the fork step used to store this value
    * @param pipeline The pipeline being processed/
    * @param steps The step lookup for the forked steps.
    * @param pipelineContext The pipeline context to clone while processing.
    * @return A list of execution results.
    */
  private def processForkStepsSerial(forkByValues: Seq[Any],
                                     firstStep: PipelineStep,
                                     forkStepId: String,
                                     pipeline: Pipeline,
                                     steps: Map[String, PipelineStep],
                                     pipelineContext: PipelineContext): List[ForkStepExecutionResult] = {
    forkByValues.zipWithIndex.map(value => {
      try {
        ForkStepExecutionResult(value._2,
          Some(executeStep(firstStep, pipeline, steps,
            createForkPipelineContext(pipelineContext, value._2).setParameterByPipelineId(pipeline.id.get,
              forkStepId, PipelineStepResponse(Some(value._1 ), None)))), None)
      } catch {
        case t: Throwable => ForkStepExecutionResult(value._2, None, Some(t))
      }
    }).toList
  }

  /**
    * Processes a set of forked steps in parallel. All values will be processed regardless of individual failures.
    * @param forkByValues The values to fork
    * @param firstStep The first step to process
    * @param forkStepId The id of the fork step used to store this value
    * @param pipeline The pipeline being processed/
    * @param steps The step lookup for the forked steps.
    * @param pipelineContext The pipeline context to clone while processing.
    * @return A list of execution results.
    */
  private def processForkStepsParallel(forkByValues: Seq[Any],
                                      firstStep: PipelineStep,
                                      forkStepId: String,
                                      pipeline: Pipeline,
                                      steps: Map[String, PipelineStep],
                                      pipelineContext: PipelineContext): List[ForkStepExecutionResult] = {
    val futures = forkByValues.zipWithIndex.map(value => {
      Future {
        try {
          ForkStepExecutionResult(value._2,
            Some(executeStep(firstStep, pipeline, steps,
            createForkPipelineContext(pipelineContext, value._2)
              .setParameterByPipelineId(pipeline.id.get,
              forkStepId, PipelineStepResponse(Some(value._1 ), None)))), None)
        } catch {
          case t: Throwable => ForkStepExecutionResult(value._2, None, Some(t))
        }
      }
    })
    // Wait for all futures to complete
    Await.ready(Future.sequence(futures), Duration.Inf)
    // Iterate the futures an extract the result
    futures.map(_.value.get.get).toList
  }

  /**
    * This function will create a new PipelineContext from the provided that includes new StepMessages
    * @param pipelineContext The PipelineContext to be cloned.
    * @param forkId The id of the fork process
    * @return A cloned PipelineContext
    */
  private def createForkPipelineContext(pipelineContext: PipelineContext, forkId: Int): PipelineContext = {
    pipelineContext.copy(stepMessages =
      Some(pipelineContext.sparkSession.get.sparkContext.collectionAccumulator[PipelineStepMessage]("stepMessages")))
      .setGlobal("forkId", forkId)
  }

  /**
    * Returns a list of steps that should be executed as part of the fork step
    * @param step The first step in the chain.
    * @param steps The full pipeline stepLookup
    * @param forkSteps The list used to store the steps
    * @return A list of steps that may be executed as part of fork processing.
    */
  private def getForkSteps(step: PipelineStep,
                           pipeline: Pipeline,
                           steps: Map[String, PipelineStep],
                           forkSteps: List[PipelineStep]): List[PipelineStep] = {
    step.`type`.getOrElse("") match {
      case "fork" => throw PipelineException(message = Some("fork steps may not be embedded other fork steps!"),
        pipelineId = pipeline.id, stepId = step.id)
      case "branch" =>
        step.params.get.foldLeft(conditionallyAddStepToList(step, forkSteps))((stepList, param) => {
          if (param.`type`.getOrElse("") == "result") {
            getForkSteps(steps(param.value.getOrElse("").asInstanceOf[String]), pipeline, steps, stepList)
          } else {
            stepList
          }
        })
      case "join" => conditionallyAddStepToList(step, forkSteps)
      case _ if !steps.contains(step.nextStepId.getOrElse("")) => conditionallyAddStepToList(step, forkSteps)
      case _ => getForkSteps(steps(step.nextStepId.getOrElse("")), pipeline, steps, conditionallyAddStepToList(step, forkSteps))
    }
  }

  /**
    * Prevents duplicate steps from being added to the list
    * @param step The step to be added
    * @param steps The list of steps to modify
    * @return A new list containing the steps
    */
  private def conditionallyAddStepToList(step: PipelineStep, steps: List[PipelineStep]): List[PipelineStep] = {
    if (steps.exists(_.id.getOrElse("") == step.id.getOrElse("NONE"))) {
      steps
    } else {
      steps :+ step
    }
  }
}

case class ForkStepResult(nextStepId: Option[String], pipelineContext: PipelineContext)
case class ForkStepExecutionResult(index: Int, result: Option[PipelineContext], error: Option[Throwable])
