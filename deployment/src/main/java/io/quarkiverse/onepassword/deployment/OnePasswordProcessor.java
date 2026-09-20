package io.quarkiverse.onepassword.deployment;

import io.quarkiverse.onepassword.OnePasswordRuntimeConfigBuilder;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigBuilderBuildItem;

class OnePasswordProcessor {
    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem("onepassword");
    }

    @BuildStep
    RunTimeConfigBuilderBuildItem runtimeConfig() {
        return new RunTimeConfigBuilderBuildItem(OnePasswordRuntimeConfigBuilder.class);
    }
}
