package com.acxiom.pipeline.applications

import com.acxiom.pipeline.drivers.DriverSetup
import com.acxiom.pipeline.utils.DriverUtils
import com.acxiom.pipeline.{CredentialProvider, PipelineContext, PipelineExecution}
import org.apache.hadoop.io.LongWritable
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.log4j.Logger
import org.apache.spark.SparkConf

trait ApplicationDriverSetup extends DriverSetup {
  val logger: Logger = Logger.getLogger(getClass)
  // Load the Application configuration
  protected def loadApplication: Application = {
    val json = if (parameters.contains("applicationJson")) {
      parameters("applicationJson").asInstanceOf[String]
    } else if (parameters.contains("applicationConfigPath")) {
      val path = parameters("applicationConfigPath").toString
      if (path.startsWith("http")) {
        DriverUtils.getHttpRestClient(path, super.credentialProvider).getStringContent("")
      } else {
        val className = parameters.getOrElse("applicationConfigurationLoader", "com.acxiom.pipeline.fs.LocalFileManager").asInstanceOf[String]
        DriverUtils.loadJsonFromFile(path, className, parameters)
      }
    } else {
      throw new RuntimeException("Either the applicationJson or the applicationConfigPath/applicationConfigurationLoader parameters must be provided!")
    }
    logger.debug(s"Loaded application json: $json")
    ApplicationUtils.parseApplication(json)
  }

  // Clean out the application properties from the parameters
  protected def cleanParams: Map[String, Any] = parameters.filterKeys {
    case "applicationJson" => false
    case "applicationConfigPath" => false
    case "applicationConfigurationLoader" => false
    case "enableHiveSupport" => false
    case "dfs-cluster" => false
    case _ => true
  }

  private lazy val application: Application = loadAndValidateApplication

  private lazy val params: Map[String, Any] = cleanParams

  private lazy val executions: List[PipelineExecution] = {
    // Configure the SparkConf
    val sparkConfOptions: Map[String, Any] = application.sparkConf.getOrElse(Map[String, Any]())
    val kryoClasses: Array[Class[_]] = if (sparkConfOptions.contains("kryoClasses")) {
      sparkConfOptions("kryoClasses").asInstanceOf[List[String]].map(c => Class.forName(c)).toArray
    } else {
      Array[Class[_]](classOf[LongWritable], classOf[UrlEncodedFormEntity])
    }
    val initialSparkConf: SparkConf = DriverUtils.createSparkConf(kryoClasses)
    val sparkConf: SparkConf = if (sparkConfOptions.contains("setOptions")) {
      sparkConfOptions("setOptions").asInstanceOf[List[Map[String, String]]].foldLeft(initialSparkConf)((conf, map) => {
        conf.set(map("name"), map("value"))
      })
    } else {
      initialSparkConf.set("spark.hadoop.io.compression.codecs",
        "org.apache.hadoop.io.compress.BZip2Codec,org.apache.hadoop.io.compress.DeflateCodec," +
          "org.apache.hadoop.io.compress.GzipCodec,org.apache." +
          "hadoop.io.compress.Lz4Codec,org.apache.hadoop.io.compress.SnappyCodec")
    }
    val credsProvider = Some(credentialProvider)
    ApplicationUtils.createExecutionPlan(
      application = application,
      globals = Some(params),
      sparkConf = sparkConf,
      applicationTriggers = ApplicationTriggers(
      enableHiveSupport = parameters.getOrElse("enableHiveSupport", false).asInstanceOf[Boolean],
      parquetDictionaryEnabled = parameters.getOrElse("parquetDictionaryEnabled", true).asInstanceOf[Boolean],
      validateArgumentTypes = parameters.getOrElse("validateStepParameterTypes", false).asInstanceOf[Boolean]),
      credentialProvider = credsProvider
    )
  }

  /**
    * This function will return the execution plan to be used for the driver.
    *
    * @since 1.1.0
    * @return An execution plan or None if not implemented
    */
  override def executionPlan: Option[List[PipelineExecution]] = Some(executions)

  override def pipelineContext: PipelineContext = executions.head.pipelineContext

  /**
    * This function allows the driver setup a chance to refresh the execution plan. This is useful in long running
    * applications such as streaming where artifacts build up over time.
    *
    * @param executionPlan The execution plan to refresh
    * @since 1.1.0
    * @return An execution plan
    */
  override def refreshExecutionPlan(executionPlan: List[PipelineExecution]): List[PipelineExecution] = {
    executionPlan.map(plan => {
      val execution = application.executions.get.find(_.id.getOrElse("") == plan.id).get
      ApplicationUtils.refreshPipelineExecution(application, Some(params), execution, plan)
    })
  }

  private def loadAndValidateApplication: Application = {
    val application = loadApplication
    DriverUtils.validateRequiredParameters(parameters, application.requiredParameters)
    application
  }

  /**
    * Returns the CredentialProvider to use during for this job. This function overrides the parent and
    * uses the application globals to instantiate the CredentialProvider.
    *
    * @return The credential provider.
    */
  override def credentialProvider: CredentialProvider = {
    DriverUtils.getCredentialProvider(application.globals.getOrElse(parameters))
  }
}

object ApplicationDriverSetup {
  def apply(parameters: Map[String, Any]): ApplicationDriverSetup = DefaultApplicationDriverSetup(parameters)
}

case class DefaultApplicationDriverSetup(parameters: Map[String, Any]) extends ApplicationDriverSetup
