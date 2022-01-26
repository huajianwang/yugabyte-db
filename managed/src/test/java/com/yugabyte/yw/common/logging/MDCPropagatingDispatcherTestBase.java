package com.yugabyte.yw.common.logging;

import static com.yugabyte.yw.common.TestHelper.testDatabase;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static play.inject.Bindings.bind;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import kamon.instrumentation.play.GuiceModule;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.modules.swagger.SwaggerModule;
import play.test.WithApplication;

public abstract class MDCPropagatingDispatcherTestBase extends WithApplication {
  private final boolean isCloud;

  protected MDCPropagatingDispatcherTestBase(boolean isCloud) {
    this.isCloud = isCloud;
  }

  @Override
  protected Application provideApplication() {
    Config config =
        ConfigFactory.parseMap(testDatabase())
            .withValue("yb.cloud.enabled", ConfigValueFactory.fromAnyRef(isCloud))
            .withValue(
                "akka.actor.default-dispatcher.type",
                ConfigValueFactory.fromAnyRef(
                    "com.yugabyte.yw.common.logging.MDCPropagatingDispatcherConfigurator"));
    return new GuiceApplicationBuilder()
        .disable(SwaggerModule.class)
        .disable(GuiceModule.class)
        .configure(config)
        .build();
  }
}
