/*
 * Copyright 2021 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.yugabyte.yw.forms.PlatformResults.YBPError;
import com.yugabyte.yw.forms.PlatformResults.YBPStructuredError;
import lombok.Getter;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;

public class PlatformServiceException extends RuntimeException {
  @Getter private final int httpStatus;
  private final String userVisibleMessage;
  private final JsonNode errJson;

  // TODO: also accept throwable and expose stack trace in when in dev server mode
  PlatformServiceException(int httpStatus, String userVisibleMessage, JsonNode errJson) {
    super(userVisibleMessage);
    this.httpStatus = httpStatus;
    this.userVisibleMessage = userVisibleMessage;
    this.errJson = errJson;
  }

  public PlatformServiceException(int httpStatus, String userVisibleMessage) {
    this(httpStatus, userVisibleMessage, null);
  }

  public PlatformServiceException(int httpStatus, JsonNode errJson) {
    this(httpStatus, "errorJson: " + errJson.toString(), errJson);
  }

  public Result buildResult(Http.RequestHeader request) {
    return buildResult(request.method(), request.uri());
  }

  @VisibleForTesting() // for routeWithYWErrHandler
  public Result buildResult(String method, String uri) {
    if (errJson == null) {
      YBPError ybpError = new YBPError(method, uri, userVisibleMessage, null);
      return Results.status(httpStatus, Json.toJson(ybpError));
    } else {
      YBPStructuredError ybpError = new YBPStructuredError(errJson);
      return Results.status(httpStatus, Json.toJson(ybpError));
    }
  }
}
