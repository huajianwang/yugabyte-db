/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.commissioner.tasks.subtasks;

import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.tasks.UniverseTaskBase;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseTaskParams;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.Universe.UniverseUpdater;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UniverseUpdateSucceeded extends UniverseTaskBase {

  @Inject
  protected UniverseUpdateSucceeded(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  // Parameters for marking universe update as a success.
  public static class Params extends UniverseTaskParams {}

  protected Params taskParams() {
    return (Params) taskParams;
  }

  @Override
  public void run() {
    try {
      log.info("Running {}", getName());

      // Create the update lambda.
      UniverseUpdater updater =
          new UniverseUpdater() {
            @Override
            public void run(Universe universe) {
              // If this universe is not being edited, fail the request.
              UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
              if (!universeDetails.updateInProgress && !universeDetails.backupInProgress) {
                log.error("Universe " + taskParams().universeUUID + " is not being edited.");
                throw new RuntimeException(
                    "Universe " + taskParams().universeUUID + " is not being edited");
              }
              // Set the operation success flag.
              universeDetails.updateSucceeded = true;
              universe.setUniverseDetails(universeDetails);
            }
          };
      // Perform the update. If unsuccessful, this will throw a runtime exception which we do not
      // catch as we want to fail.
      saveUniverseDetails(updater);

    } catch (Exception e) {
      String msg = getName() + " failed with exception " + e.getMessage();
      log.warn(msg, e.getMessage());
      throw new RuntimeException(msg, e);
    }
  }
}
