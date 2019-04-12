import com.nestle.NesBuilder


def call(config) {

  
  /**
     * Users to ignore
     */
    config.UsersToIgnore = ['jenkins']

    /**
     * The branch from which manual builds trigger a release
     */
    config.ReleaseBranch = 'master'
    config.PreviewBranchPrefix = 'preview'

    config.MavenProperties = [
            path         : 'gradle.properties',
            versionKey   : 'version',
            appVersionKey: 'appVersionCode',
            groupKey     : 'group'
    ]

    /**
     * The name of the AVD target
     */
    config.TargetName = "android-27"

    /**
     * Empty flavorNames array means all available flavors, in multi-flavor projects flavorNames shall contain the required flavor combination
    */
    config.BuildTypes = [
            release: [name       : 'release',
                      flavorNames: []],
            debug  : [name       : 'debug',
                      flavorNames: []]
    ]

    config.Signing = [
            keystoreLocation     : '$HOME/.mobile/nestle.keystore',
            propertyFileLocation : '$HOME/.mobile/secure.properties',
            localPropertyFileName: 'secure.properties',
            keystorePropertyName : 'key.store.location'
    ]
    config.AppArtifactTypes = [
            app    : 'apk',
            mapping: 'mapping'
    ]

    config.NexusRepository = 'nespresso-mobileapps-repository'

}
