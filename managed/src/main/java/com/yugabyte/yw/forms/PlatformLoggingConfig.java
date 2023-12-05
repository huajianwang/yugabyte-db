// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.forms;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import javax.annotation.Nullable;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import lombok.Data;
import org.slf4j.event.Level;

@Data
public class PlatformLoggingConfig {

  @NotNull
  @Enumerated(EnumType.STRING)
  private Level level;

  @Nullable private String rolloverPattern;

  @Nullable
  @Min(value = 0)
  private Integer maxHistory;
}
