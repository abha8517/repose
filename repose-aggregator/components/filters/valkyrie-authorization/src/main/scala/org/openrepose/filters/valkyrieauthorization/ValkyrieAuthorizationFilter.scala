package org.openrepose.filters.valkyrieauthorization

import java.io.InputStream
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.regex.{Matcher, PatternSyntaxException}
import javax.inject.{Inject, Named}
import javax.servlet._
import javax.servlet.http.{HttpServletRequest, HttpServletResponse, HttpServletResponseWrapper}

import com.fasterxml.jackson.core.JsonParseException
import com.josephpconley.jsonpath.JSONPath
import com.rackspace.httpdelegation.HttpDelegationManager
import com.typesafe.scalalogging.slf4j.LazyLogging
import io.gatling.jsonpath.AST.{Field, PathToken, RootNode}
import org.openrepose.commons.config.manager.UpdateListener
import org.openrepose.commons.utils.http.{CommonHttpHeader, OpenStackServiceHeader, ServiceClientResponse}
import org.openrepose.commons.utils.servlet.http.{MutableHttpServletRequest, MutableHttpServletResponse}
import org.openrepose.core.filter.FilterConfigHelper
import org.openrepose.core.services.config.ConfigurationService
import org.openrepose.core.services.datastore.DatastoreService
import org.openrepose.core.services.serviceclient.akka.AkkaServiceClient
import org.openrepose.filters.valkyrieauthorization.config._
import play.api.libs.json._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.io.Source
import scala.util.{Failure, Success, Try}

@Named
class ValkyrieAuthorizationFilter @Inject()(configurationService: ConfigurationService, akkaServiceClient: AkkaServiceClient, datastoreService: DatastoreService)
  extends Filter
  with UpdateListener[ValkyrieAuthorizationConfig]
  with HttpDelegationManager
  with LazyLogging {

  private final val DEFAULT_CONFIG = "valkyrie-authorization.cfg.xml"
  val datastore = datastoreService.getDefaultDatastore

  var configurationFile: String = DEFAULT_CONFIG
  var configuration: ValkyrieAuthorizationConfig = _
  var initialized = false

  override def init(filterConfig: FilterConfig): Unit = {
    configurationFile = new FilterConfigHelper(filterConfig).getFilterConfig(DEFAULT_CONFIG)
    logger.info("Initializing filter using config " + configurationFile)
    val xsdURL: URL = getClass.getResource("/META-INF/schema/config/valkyrie-authorization.xsd")
    configurationService.subscribeTo(
      filterConfig.getFilterName,
      configurationFile,
      xsdURL,
      this,
      classOf[ValkyrieAuthorizationConfig]
    )
  }

  override def destroy(): Unit = {
    configurationService.unsubscribeFrom(configurationFile, this)
  }

  trait ValkyrieResult

  case class DeviceToPermission(device: Int, permission: String)
  case class DeviceList(devices: Vector[DeviceToPermission]) extends ValkyrieResult //Vector because List isnt serializable until Scala 2.11
  case class ResponseResult(statusCode: Int, message: String = "") extends ValkyrieResult

  override def doFilter(servletRequest: ServletRequest, servletResponse: ServletResponse, filterChain: FilterChain): Unit = {
    val mutableHttpRequest = MutableHttpServletRequest.wrap(servletRequest.asInstanceOf[HttpServletRequest])
    val mutableHttpResponse = MutableHttpServletResponse.wrap(mutableHttpRequest, new HttpServletResponseWrapper(servletResponse.asInstanceOf[HttpServletResponse]))

    def nullOrWhitespace(str: Option[String]): Option[String] = str.map { _.trim }.filter { !"".equals(_) }

    val requestedTenantId = nullOrWhitespace(Option(mutableHttpRequest.getHeader(OpenStackServiceHeader.TENANT_ID.toString)))
    val requestedDeviceId = nullOrWhitespace(Option(mutableHttpRequest.getHeader("X-Device-Id")))
    val requestedContactId = nullOrWhitespace(Option(mutableHttpRequest.getHeader("X-Contact-Id")))
    val requestGuid = nullOrWhitespace(Option(mutableHttpRequest.getHeader(CommonHttpHeader.TRACE_GUID.toString)))
    val urlPath: String = new URL(mutableHttpRequest.getRequestURL.toString).getPath

    def getDeviceList(tenantId: Option[String], contactId: Option[String]): ValkyrieResult = {
      def convertToDeviceList(value: java.io.Serializable): DeviceList = DeviceList(value.asInstanceOf[Vector[DeviceToPermission]])

      (requestedTenantId, requestedContactId) match {
        case (None, _) => ResponseResult(401, "No tenant ID specified")
        case (Some(tenant), _) if "(hybrid:.*)".r.findFirstIn(tenant).isEmpty => ResponseResult(403, "Not Authorized")
        case (_, None) => ResponseResult(401, "No contact ID specified")
        case (Some(tenant), Some(contact)) =>
          val transformedTenant = tenant.substring(tenant.indexOf(":") + 1, tenant.length)
          datastoreValue(transformedTenant, contact, "devices", configuration.getValkyrieServer, convertToDeviceList, parseDevices, requestGuid)
      }
    }

    def authorizeDevice(deviceList: ValkyrieResult, deviceId: Option[String]): ResponseResult = {
      (deviceList, deviceId) match {
        case (response: ResponseResult, _) => response
        case (devicePermissions: DeviceList, None) => ResponseResult(200)
        case (devicePermissions: DeviceList, Some(device)) => authorize(device, devicePermissions.devices, mutableHttpRequest.getMethod)
      }
    }

    def mask403s(valkyrieResponse: ResponseResult): ResponseResult = {
      valkyrieResponse match {
        case ResponseResult(403, _) if configuration.isEnableMasking403S => ResponseResult(404, "Not Found")
        case result => result
      }
    }

    val devicePermissions: ValkyrieResult = getDeviceList(requestedTenantId, requestedContactId)
    mask403s(authorizeDevice(devicePermissions, requestedDeviceId)) match {
      case ResponseResult(200, _) =>
        filterChain.doFilter(mutableHttpRequest, mutableHttpResponse)
        try {
          cullResponse(urlPath, mutableHttpResponse, devicePermissions.asInstanceOf[DeviceList])
        } catch {
          case rce: ResponseCullingException =>
            logger.debug("Failed to cull response, wiping out response.", rce)
            mutableHttpResponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, rce.message)
        }
      case ResponseResult(code, message) if Option(configuration.getDelegating).isDefined =>
        buildDelegationHeaders(code, "valkyrie-authorization", message, configuration.getDelegating.getQuality).foreach { case (key, values) =>
          values.foreach { value => mutableHttpRequest.addHeader(key, value) }
        }
        filterChain.doFilter(mutableHttpRequest, mutableHttpResponse)
      case ResponseResult(code, message) =>
        mutableHttpResponse.sendError(code, message)
    }

    //weep for what we must do with this flaming pile, note above where it was wrapped pointlessly just to break the chain
    mutableHttpResponse.writeHeadersToResponse()
    mutableHttpResponse.commitBufferToServletOutputStream()
  }

  def cacheKey(typeOfCall: String, transformedTenant: String, contactId: String): String = {
    ValkyrieAuthorizationFilter.CACHE_PREFIX + typeOfCall + transformedTenant + contactId
  }

  def datastoreValue(transformedTenant: String,
                     contactId: String,
                     callType: String,
                     valkyrieServer: ValkyrieServer,
                     datastoreTransform: java.io.Serializable => ValkyrieResult,
                     responseParser: InputStream => Try[java.io.Serializable],
                     requestGuid: Option[String] = None): ValkyrieResult = {
    def tryValkyrieCall(): Try[ServiceClientResponse] = {
      import collection.JavaConversions._

      val requestGuidHeader = requestGuid.map(guid => Map(CommonHttpHeader.TRACE_GUID.toString -> guid)).getOrElse(Map())
      val uri = valkyrieServer.getUri + s"/account/$transformedTenant/permissions/contacts/$callType/by_contact/$contactId/effective"
      Try(akkaServiceClient.get(cacheKey(callType, transformedTenant, contactId),
        uri,
        Map("X-Auth-User" -> valkyrieServer.getUsername, "X-Auth-Token" -> valkyrieServer.getPassword) ++ requestGuidHeader)
      )
    }

    def valkyrieAuthorize(): ValkyrieResult = {
      tryValkyrieCall() match {
        case Success(response) =>
          if (response.getStatus == 200) {
            responseParser(response.getData) match {
              case Success(values) =>
                datastore.put(cacheKey(callType, transformedTenant, contactId), values, configuration.getCacheTimeoutMillis, TimeUnit.MILLISECONDS)
                datastoreTransform(values)
              case Failure(x) => ResponseResult(502, x.getMessage) //JSON Parsing failure
            }
          } else {
            ResponseResult(502, s"Valkyrie returned a ${response.getStatus}") //Didn't get a 200 from valkyrie
          }
        case Failure(exception) =>
          ResponseResult(502, s"Unable to communicate with Valkyrie: ${exception.getMessage}")
      }
    }

    Option(datastore.get(cacheKey(callType, transformedTenant, contactId))) match {
      case Some(x) => datastoreTransform(x)
      case None => valkyrieAuthorize()
    }
  }

  def authorize(deviceId: String, deviceList: Vector[DeviceToPermission], method: String): ResponseResult = {
    deviceList.find(_.device.toString == deviceId).map { deviceToPermission =>
      deviceToPermission.permission match {
        case "view_product" if List("GET", "HEAD").contains(method) => ResponseResult(200)
        case "edit_product" => ResponseResult(200)
        case "admin_product" => ResponseResult(200)
        case _ => ResponseResult(403, "Not Authorized")
      }
    } getOrElse {
      ResponseResult(403, "Not Authorized")
    }
  }

  def parseDevices(is: InputStream): Try[Vector[DeviceToPermission]] = {
    import play.api.libs.functional.syntax._
    import play.api.libs.json.Reads._
    import play.api.libs.json._

    implicit val deviceToPermissions = (
      (JsPath \ "item_id").read[Int] and
        (JsPath \ "permission_name").read[String]
      )(DeviceToPermission.apply _)

    val input: String = Source.fromInputStream(is).getLines() mkString ""
    try {
      val json = Json.parse(input)
      (json \ "contact_permissions").validate[Vector[DeviceToPermission]] match {
        case s: JsSuccess[Vector[DeviceToPermission]] =>
          Success(s.get)
        case f: JsError =>
          logger.error(s"Valkyrie Response did not match expected contract: ${
            JsError.toFlatJson(f)
          }")
          Failure(new Exception("Valkyrie Response did not match expected contract"))
      }
    } catch {
      case e: Exception =>
        logger.error(s"Invalid Json response from Valkyrie: $input", e)
        Failure(new Exception("Invalid Json response from Valkyrie"))
    }
  }


  def cullResponse(urlPath: String, response: MutableHttpServletResponse, devicePermissions: DeviceList): Unit = {

    def getJsPathFromString(jsonPath: String): JsPath = {
      val pathTokens: List[PathToken] = JSONPath.parser.compile(jsonPath).getOrElse({
        throw new ResponseCullingException(s"Unable to parse JsonPath: $jsonPath")
      })
      pathTokens.foldLeft(new JsPath) { (path, token) =>
        token match {
          case RootNode => path
          case Field(name) => path \ name
        }
      }
    }

    def cullJsonArray(jsonArray: Seq[JsValue], devicePath: DevicePath): Seq[JsValue] = {
      jsonArray.filter { value =>
        val deviceValue: String = Try(JSONPath.query(devicePath.getPath, value).as[String])
          .recover( { case jre: JsResultException => throw new ResponseCullingException(s"Invalid path specified for device id: ${devicePath.getPath}", jre) } )
          .get

        try {
          val matcher: Matcher = devicePath.getRegex.getValue.r.pattern.matcher(deviceValue)
          if (matcher.matches()) {
            devicePermissions.devices.filter(_.device == matcher.group(devicePath.getRegex.getCaptureGroup).toInt).nonEmpty
          } else {
            throw new ResponseCullingException(s"Regex: ${devicePath.getRegex.getValue} did not match $deviceValue")
          }
        } catch {
          case pse: PatternSyntaxException => throw new ResponseCullingException("Unable to parse regex for device id", pse)
          case ioobe: IndexOutOfBoundsException => throw new ResponseCullingException("Bad capture group specified", ioobe)
        }
      }
    }

    def updateItemCount(json: JsObject, pathToItemCount: String, newCount: Int): JsObject = {
      Option(pathToItemCount) match {
        case Some(path) =>
          JSONPath.query(path, json) match {
            case undefined: JsUndefined => throw new ResponseCullingException(s"Invalid path specified for item count: $path")
            case _ =>
              val countTransform: Reads[JsObject] = getJsPathFromString(path).json.update(__.read[JsNumber].map { _ => new JsNumber(newCount) })
              json.transform(countTransform).getOrElse {
                throw new ResponseCullingException("Unable to transform json while updating the count.")
              }
          }
        case None => json
      }
    }

    val matchingResources: Option[mutable.Buffer[Resource]] = Option(configuration.getCollectionResources)
      .map(_.getResource.asScala.filter(_.getPathRegex.r.pattern.matcher(urlPath).matches()))
    if (matchingResources.isDefined && matchingResources.get.nonEmpty) {
      val input: String = Source.fromInputStream(response.getBufferedOutputAsInputStream).getLines() mkString ""
      val initialJson: JsValue = Try(Json.parse(input))
        .recover( { case jpe: JsonParseException => throw new ResponseCullingException("Response contained improper json.", jpe) } )
        .get
      val finalJson = matchingResources.get.foldLeft(initialJson) { (resourceJson, resource) =>
        resource.getCollection.asScala.foldLeft(resourceJson) { (collectionJson, collection) =>
          val array: Seq[JsValue] = Try(JSONPath.query(collection.getJson.getPathToCollection, collectionJson).as[Seq[JsValue]])
            .recover( { case jre: JsResultException => throw new ResponseCullingException(s"Invalid path specified for collection: ${collection.getJson.getPathToCollection}", jre) } )
            .get

          val culledArray: Seq[JsValue] = cullJsonArray(array, collection.getJson.getPathToDeviceId)

          //these are a little complicated, look here for details: https://www.playframework.com/documentation/2.2.x/ScalaJsonTransformers
          val arrayTransform: Reads[JsObject] = getJsPathFromString(collection.getJson.getPathToCollection).json.update(__.read[JsArray].map { _ => new JsArray(culledArray) })
          val transformedJson = collectionJson.transform(arrayTransform).getOrElse({
            throw new ResponseCullingException("Unable to transform json while culling list.")
          })

          updateItemCount(transformedJson, collection.getJson.getPathToItemCount, culledArray.size)
        }
      }
      response.getOutputStream.print(finalJson.toString())
    }
  }

  override def configurationUpdated(configurationObject: ValkyrieAuthorizationConfig): Unit = {
    configuration = configurationObject
    initialized = true
  }

  override def isInitialized: Boolean = initialized
}

object ValkyrieAuthorizationFilter {
  val CACHE_PREFIX = "VALKYRIE-FILTER"
}

case class ResponseCullingException(message: String, throwable: Throwable) extends Exception(message, throwable) {
  def this(message: String) = this(message, null)
}
