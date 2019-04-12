/**
 * Configuration parameters for Android flow
 */

def call(config) {

    /**
     * Default emails
     */
    // config.AndroidManagerEmail = "kaushal.kapoor@es.nestle.com"

    /**
     * Users to ignore
     */
    config.UsersToIgnore = ['jenkins']

    /**
     * Node label to use
     */
    config.NodeLabel = "android-sdk"

    /**
     * The branch from which manual builds trigger a release
     */
    config.ReleaseBranch = 'develop'

    config.MavenProperties = [
            path         : 'gradle.properties',
            versionKey   : 'version',
            appVersionKey: 'appVersionCode',
            groupKey     : 'group'
    ]

    /**
     * Empty flavors array means all available flavors
     */
    config.BuildTypes = [
            release: [name   : 'release',
                      flavors: []],
            debug  : [name   : 'debug',
                      flavors: []]
    ]

    config.BuildOutputsFolder = "./app/build/outputs"

    /**
     * HockeyApp parameters
     */
    config.HockeyAppReleaseId = ''
    config.HockeyAppBetaId = ''
    config.HockeyAppDebugId = ''

    /**
     * Nexus parameters
     */
    config.NexusRepository = 'bcn-snapshots-hub'
    config.NexusGroupId = "com.nestle.apps.android"


    /**
     * Build keys
     */
    config.AndroidKeyAliasPassCredentialId = 'KEY_ALIAS_PASS'
    config.AndroidKeyPathCredentialId = 'KEY_PATH'
    config.AndroidKeyStorePathCredentialId = 'KEY_STORE_PASS'

    /**
     * Post-build actions
     */
    config.PostBuildActions = ['errorEmailNotification','actionCleanWs']

    /**
     * Emails to filter
     */
    config.mailer.DefaultAddressFilter = []
    config.mailer.FallbackMailList = [config.AndroidManagerEmail]
}
