/**
 * Copyright (c) YugaByte, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.  See the License for the specific language governing permissions and limitations
 * under the License.
 *
 */
package org.yb.util;

import org.junit.runners.model.InitializationError;
import org.yb.YBTestRunner;

/**
 * Think twice if you really want to limit your tests this much!
 * <p>
 * Basically should only be used for using older YSQL snapshot, and should be removed after #10116.
 */
public class YBTestRunnerReleaseOnly extends YBTestRunner {

  public YBTestRunnerReleaseOnly(Class<?> klass) throws InitializationError {
    super(klass);
  }

  @Override
  public boolean shouldRunTests() {
    return BuildTypeUtil.isRelease();
  }

}
