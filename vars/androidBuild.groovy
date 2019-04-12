import com.nestle.NesAndroidBuilder



def runBuildFlow(NesAndroidBuilder builder) {
    def isRelease = builder.getUserId() && builder.getBranchName() == builder.config.ReleaseBranch
    builder.config.default_node_label = builder.pickNodeNameByLabel("android-sdk")
    builder.config.PreviewBranchPrefix = builder.config.PreviewBranchPrefix ?: 'preview'
    def isPreview = builder.getBranchName().startsWith(builder.config.PreviewBranchPrefix)

    stage("Clean workspace") {
        builder.actionCleanWs()
    }

    stage("Checkout") {      //(3.1)
        builder.actionVCSCheckout(branch = builder.getBranchName())
    }

    // Lock node to avoid sharing resource outside workspace
        builder.withNode(builder.config.default_node_label) {


    stage("Build") {          //(3.2)
      builder.actionRunShell("docker -u root -v $RUTA:/app -it corleone77/fastlane:v1 fastlane deploy")

                }
             }
    }


def call(body) {                        //(4)

    def builder = new NesBuilder(this)  //(5)
    builder.applyConfig(body)           //(6)

    builder.exec(this.&runBuildFlow)    //(7)
}
