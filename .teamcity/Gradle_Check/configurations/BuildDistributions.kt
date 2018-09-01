package configurations

import jetbrains.buildServer.configs.kotlin.v2018_1.AbsoluteId
import model.CIBuildModel
import model.Stage

class BuildDistributions(model: CIBuildModel, stage: Stage) : BaseGradleBuildType(model, stage = stage, init = {
    uuid = "${model.projectPrefix}BuildDistributions"
    id = AbsoluteId(uuid)
    name = "Build Distributions"
    description = "Creation and verification of the distribution and documentation"

    applyDefaults(model, this, "packageBuild", extraParameters = buildScanTag("BuildDistributions") + " -PtestJavaHome=%linux.java8.oracle.64bit%", daemon = false)

    artifactRules = """$artifactRules
        build/distributions/*.zip => distributions
        build/build-receipt.properties
    """.trimIndent()

    params {
        param("env.JAVA_HOME", buildJavaHome)
    }
})