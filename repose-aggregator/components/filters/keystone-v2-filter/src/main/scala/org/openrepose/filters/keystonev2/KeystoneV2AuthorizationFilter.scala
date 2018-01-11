/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package org.openrepose.filters.keystonev2

import javax.inject.{Inject, Named}
import javax.servlet.ServletRequest
import javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR

import org.openrepose.commons.utils.http.OpenStackServiceHeader.TENANT_ID
import org.openrepose.commons.utils.servlet.http.HttpServletRequestWrapper
import org.openrepose.core.services.config.ConfigurationService
import org.openrepose.filters.keystonev2.AbstractKeystoneV2Filter.{KeystoneV2Result, Reject}
import org.openrepose.filters.keystonev2.KeystoneV2Authorization.{AuthorizationFailed, AuthorizationPassed, doAuthorization}
import org.openrepose.filters.keystonev2.KeystoneV2Common.{EndpointsData, EndpointsRequestAttributeName, TokenRequestAttributeName, ValidToken}
import org.openrepose.filters.keystonev2.config.KeystoneV2Config

import scala.util.{Failure, Success, Try}

@Named
class KeystoneV2AuthorizationFilter @Inject()(configurationService: ConfigurationService)
  extends AbstractKeystoneV2Filter[KeystoneV2Config](configurationService) {

  import KeystoneV2AuthorizationFilter._

  override val DEFAULT_CONFIG = "keystone-v2-authorization.cfg.xml"
  override val SCHEMA_LOCATION = "/META-INF/schema/config/keystone-v2-authorization.xsd"

  override val handleFailures: PartialFunction[Try[Unit.type], KeystoneV2Result] = {
    KeystoneV2Authorization.handleFailures orElse {
      case Failure(e@(_: MissingTokenException |
                      _: MissingEndpointsException |
                      _: InvalidTokenException |
                      _: InvalidEndpointsException)) =>
        Reject(SC_INTERNAL_SERVER_ERROR, Some(e.getMessage))
    }
  }

  override def doAuth(request: HttpServletRequestWrapper): Try[Unit.type] = {
    getToken(request) flatMap { token =>
      doAuthorization(configuration, request, token, getEndpoints(request)) match {
        case AuthorizationPassed(_, Some(matchedTenant)) =>
          scopeTenantIdHeader(request, matchedTenant)
          Success(Unit)
        case AuthorizationPassed(_, None) => Success(Unit)
        case AuthorizationFailed(_, _, exception) => Failure(exception)
      }
    }
  }

  def getToken(request: ServletRequest): Try[ValidToken] = {
    Try {
      Option(request.getAttribute(TokenRequestAttributeName)).get.asInstanceOf[ValidToken]
    } recover {
      case nsee: NoSuchElementException => throw MissingTokenException("Token request attribute does not exist", nsee)
      case cce: ClassCastException => throw InvalidTokenException("Token request attribute is not a valid token", cce)
    }
  }

  def getEndpoints(request: ServletRequest): Try[EndpointsData] = {
    Try {
      Option(request.getAttribute(EndpointsRequestAttributeName)).get.asInstanceOf[EndpointsData]
    } recover {
      case nsee: NoSuchElementException => throw MissingEndpointsException("Endpoints request attribute does not exist", nsee)
      case cce: ClassCastException => throw InvalidEndpointsException("Endpoints request attribute is not a valid endpoints object", cce)
    }
  }

  def scopeTenantIdHeader(request: HttpServletRequestWrapper, matchedTenant: String): Unit = {
    val tenantHandling = Option(configuration.getTenantHandling)
    val sendAllTenantIds = tenantHandling.exists(_.isSendAllTenantIds)
    val matchedTenantQuality = tenantHandling.map(_.getSendTenantIdQuality).flatMap(Option.apply).map(_.getUriTenantQuality)

    (sendAllTenantIds, matchedTenantQuality) match {
      case (true, Some(quality)) => request.addHeader(TENANT_ID, matchedTenant, quality)
      case (true, None) => request.addHeader(TENANT_ID, matchedTenant)
      case (false, Some(quality)) => request.replaceHeader(TENANT_ID, matchedTenant, quality)
      case (false, None) => request.replaceHeader(TENANT_ID, matchedTenant)
    }
  }
}

object KeystoneV2AuthorizationFilter {

  case class MissingTokenException(message: String, cause: Throwable = null) extends Exception(message, cause)

  case class MissingEndpointsException(message: String, cause: Throwable = null) extends Exception(message, cause)

  case class InvalidTokenException(message: String, cause: Throwable = null) extends Exception(message, cause)

  case class InvalidEndpointsException(message: String, cause: Throwable = null) extends Exception(message, cause)

}
