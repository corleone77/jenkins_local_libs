package com.nestle

import com.cloudbees.groovy.cps.NonCPS
import groovy.text.SimpleTemplateEngine

/**
 * Android builder class
 */
@groovy.transform.InheritConstructors
class NesAndroidBuilder extends NesBuilder implements Serializable {
    private tasks
    private nesHockeyApp

    def setUp() {
        withNode {
            info("Running on " + actionRunShell("hostname", true) + " triggered by ${getUserId() ?: 'code change'}")
            context.env.ANDROID_HOME = config.android.sdklocation
            def keystoreProperties = new Properties()
            def keystorePropertiesContent = actionRunShell("cat $config.Signing.propertyFileLocation", true)
            if (keystorePropertiesContent) {
                keystoreProperties.load(new StringReader(keystorePropertiesContent))
                keystoreProperties << [("$config.Signing.keystorePropertyName" as String): actionRunShell("echo $config.Signing.keystoreLocation", true)]
                context.writeFile(file: "$config.Signing.localPropertyFileName", text: keystoreProperties
                        .collect { "$it.key=$it.value" }
                        .join('\n'))
            } else {
                warn("Keystore properties file cannot be loaded, signing (if required) will probably fail")
            }
        }
    }

    def actionCompile(isRelease = false) {
        actionRunGradle(getMatchingTasks("compile", "sources", isRelease))
    }



    def updateAndPushReleaseVersion(userId = getUserId()) {
        updateProjectVersion(getNextProjectVersion(true))
        def applicationVersion = getApplicationVersion()
        if (applicationVersion) {
            updateApplicationVersion(computeNextVersion(applicationVersion, true))
        }
        actionRunShell("git commit --allow-empty -m \"Update release version on behalf of $userId. ${context.env.BUILD_URL}\" ${config['MavenProperties'].path} && git push origin HEAD:\$BRANCH_NAME")
    }

    //Called by "reflection" :(
    def updateAndPushSnapshotVersion(userId = getUserId()) {
        updateProjectVersion(getNextProjectVersion(false))
        actionRunShell("git commit --allow-empty -m \"Update snapshot version on behalf of $userId. ${context.env.BUILD_URL}\" ${config['MavenProperties'].path} && git push origin HEAD:\$BRANCH_NAME")
    }

    def killAndroidAvdAndNodeJs() {
        actionRunShell(script: "pkill --signal 9 -f qemu-system-x86_64.*buildavd", returnStatus: true)
        actionRunShell(script: "pkill --signal 9 node", returnStatus: true)
    }

    def createAndPushTag() {
        actionPushTag(getProjectVersion(), config.gitTagPrefix)
    }

    def actionGenerateArtifacts(isRelease = false) {
        actionRunGradle(getMatchingTasks("assemble", "Artifacts", isRelease))
    }

    def actionUploadArtifacts(artifacts, isRelease = false) {
        def resolvedArtifacts = resolveAllArtifacts(artifacts, isRelease)
                .findAll { it.artifactId || config.AppArtifactTypes.values().contains(it.type) }
                .split { it.artifactId && !config.AppArtifactTypes.values().contains(it.type) }
        uploadToNexus(resolvedArtifacts[0])
        uploadToHockeyApp(resolvedArtifacts[1])
    }

    def actionUploadPreview(artifacts) {
        def resolvedArtifacts = resolveAllArtifacts(artifacts, false)
                .findAll { it.artifactId || config.AppArtifactTypes.values().contains(it.type) }
                .split { it.artifactId && !config.AppArtifactTypes.values().contains(it.type) }

        resolvedArtifacts.each {
            if (!it.empty) {
                def apkPaths = it.findAll {
                    it.type == 'apk'
                }

                apkPaths.each {
                    actionUploadToMinio(it.file, config.previewServer, config.previewRepo)
                }
            }
        }
    }

    private resolveAllArtifacts(artifacts, isRelease) {
        def buildType = getBuildType(isRelease)
        artifacts.collectMany { artifact ->
            getFlavorNames(isRelease).collect { flavorName ->
                def noFlavorIfEmpty = flavorName ?: null
                def artifactDescription = artifact.clone()
                if (artifactDescription.artifactId) {
                    artifactDescription << [artifactId: new SimpleTemplateEngine()
                            .createTemplate(artifactDescription.artifactId)
                            .make(buildType: buildType, flavorName: noFlavorIfEmpty) as String]
                }
                if (artifactDescription.classifier) {
                    artifactDescription << [classifier: new SimpleTemplateEngine()
                            .createTemplate(artifactDescription.classifier)
                            .make(buildType: buildType, flavorName: noFlavorIfEmpty) as String]
                }
                def artifactLocationPattern = new SimpleTemplateEngine().createTemplate(artifactDescription.file)
                        .make(buildType: buildType, flavorName: noFlavorIfEmpty) as String
                withNode {
                    def foundArtifacts = context.findFiles(glob: artifactLocationPattern)
                    if (!foundArtifacts) {
                        actionFailBuild("Artifact cannot be found with pattern $artifactLocationPattern")
                    }
                    artifactDescription << [file: foundArtifacts.first().path]
                }
            }
        }.unique()
    }

    private uploadToNexus(libraryArtifacts) {
        withNode {
            context.nexusArtifactUploader(
                    artifacts: libraryArtifacts,
                    credentialsId: config.NexusCredentialsId,
                    groupId: getMavenProperty("groupKey"),
                    nexusUrl: config.NexusUrl,
                    nexusVersion: config.NexusVersion,
                    protocol: config.NexusProtocol,
                    repository: config.NexusRepository,
                    version: getProjectVersion()
            )
        }
    }

    private uploadToHockeyApp(appArtifacts) {
        appArtifacts.groupBy { artifactDescription ->
            def elementRegex = '[\\S&&[^./-]]+'
            def artifactMatcher = artifactDescription.file =~ /^(?i)(?<module>$elementRegex)\/build\/outputs\/${artifactDescription.type}(\/(?<flavorName>$elementRegex))?\/(?<buildType>$elementRegex)\/[^\/]+$/
            if (!artifactMatcher) {
                actionFailBuild("Cannot resolve module, flavorNames and build type for artifact ${artifactDescription.file}")
            }
            def group = [
                    module : artifactMatcher.group('module'),
                    variant: [
                            buildType: artifactMatcher.group('buildType')
                    ]
            ]
            def flavorName = artifactMatcher.group('flavorName')
            if (flavorName) {
                group.variant << [flavorName: flavorName]
            }
            group
        }.each { currentModuleBuildTypeAndFlavorName, artifactList ->
            def hockeyAppIdentifier = resolveHockeyAppIdentifier(currentModuleBuildTypeAndFlavorName)
            if (!hockeyAppIdentifier) {
                actionFailBuild("Cannot found HockeyApp identifier, check the configuration")
            }
            nesHockeyApp = nesHockeyApp ?: new NesHockeyApp(this)
            def apkPath = artifactList.find { it.type == config.AppArtifactTypes.app }?.file
            def mappingPath = artifactList.find { it.type == config.AppArtifactTypes.mapping }?.file
            nesHockeyApp.uploadToHockeyApp(apkPath, mappingPath, config.HockeyAppUploadToken, hockeyAppIdentifier)
        }
    }

    private resolveHockeyAppIdentifier(currentModuleBuildTypeAndFlavorName) {
        def manifest = readManifest(currentModuleBuildTypeAndFlavorName)

        if (!manifest) {
            actionFailBuild("Android manifest file is empty.")
        }

        findHockeyAppNode(new XmlSlurper()
                .parseText(manifest)
                .declareNamespace('android': 'http://schemas.android.com/apk/res/android')
                .application
                .'meta-data')?.'@android:value'?.text()
    }

    @NonCPS
    private findHockeyAppNode(metaData) {
        metaData.find { 'net.hockeyapp.android.appIdentifier' == it.'@android:name'.text() }
    }

    private readManifest(currentModuleBuildTypeAndFlavorName) {
        def manifestPath = "${currentModuleBuildTypeAndFlavorName.module}/build/intermediates/manifests/full/${currentModuleBuildTypeAndFlavorName.variant.flavorName?.concat('/') ?: ''}${currentModuleBuildTypeAndFlavorName.variant.buildType}/AndroidManifest.xml"

        try {
            actionReadFile(manifestPath)
        } catch (ignored) {
            actionFailBuild("Android manifest file not found. [PATH=$manifestPath]")
        }
    }

    private getMatchingTasks(prefix, suffix, isRelease = false) {
        def flavorNames = isRelease ? config.BuildTypes.release.flavorNames : config.BuildTypes.debug.flavorNames
        tasks = tasks ?: actionRunGradle(arguments: ['tasks', '--all', '--quiet'], returnStdout: true).split('\n').findAll { it }
        def patternStr = "(?i)(\\S+:" + (prefix ?: "") + '(' + (flavorNames.join('|') ?: "\\S+") + ')?' + getBuildType(isRelease) + (suffix ?: "") + ")"
        def matchingTasks = tasks.findAll { it =~ /$patternStr/ }.collect { (it =~ /$patternStr/)[0][0] }
        if (!matchingTasks) {
            actionFailBuild("No task found for prefix ($prefix), suffix ($suffix), release ($isRelease)")
        }
        matchingTasks
    }

    private getBuildType(isRelease) {
        isRelease ? config.BuildTypes.release.name : config.BuildTypes.debug.name
    }

    private getFlavorNames(isRelease) {
        def arbitraryFlavorNameTaskPrefix = "check"
        def arbitraryFlavorNameTaskSuffix = "manifest"
        getMatchingTasks(arbitraryFlavorNameTaskPrefix, arbitraryFlavorNameTaskSuffix, isRelease).collect {
            def patternStr = "(?i)\\S+:$arbitraryFlavorNameTaskPrefix(?<flavorName>\\S+)${getBuildType(isRelease)}$arbitraryFlavorNameTaskSuffix"
            def match = (it =~ /$patternStr/)
            if (match) {
                match.group('flavorName').uncapitalize()
            } else {
                ""
            }
        }.unique().findAll { it } ?: [""]
    }

    private getProjectVersion() {
        getMavenProperty("versionKey")
    }

    private getApplicationVersion() {
        getMavenProperty("appVersionKey")
    }

    private getMavenProperty(configurationKey, mandatory = true) {
        withNode {
            def mavenPropertiesPath = config.MavenProperties.path
            if (!context.fileExists(mavenPropertiesPath)) {
                actionFailBuild("Failed to read the maven property file ($mavenPropertiesPath) as it cannot be read")
            }
            def mavenPropertiesKey = config.MavenProperties[configurationKey]
            def propertyValue = context.readProperties(file: mavenPropertiesPath)[mavenPropertiesKey]
            if (mandatory && !propertyValue) {
                actionFailBuild("Failed to read the property: key ($mavenPropertiesKey) not present in file ($mavenPropertiesPath)")
            }
            propertyValue
        }
    }

    private getNextProjectVersion(releaseVersion) {
        computeNextVersion(getProjectVersion(), releaseVersion)
    }

    private computeNextVersion(currentVersion, releaseVersion) {
        info("Computing next version of $currentVersion")
        def versionGroups = (currentVersion =~ /^(.*\D)?(\d+)(-SNAPSHOT)?$/)[0]
        def newVersion = (versionGroups[1] ?: "")
        if (versionGroups[3]) { // the current version is a snapshot
            if (releaseVersion) {
                newVersion += versionGroups[2]
            } else {
                newVersion = currentVersion
            }
        } else {
            newVersion += (versionGroups[2].toInteger() + 1) + (releaseVersion ? '' : "-SNAPSHOT")
        }
        newVersion
    }

    private updateProjectVersion(newVersion) {
        updateVersion(newVersion, 'versionKey')
    }

    private updateApplicationVersion(newVersion) {
        updateVersion(newVersion, 'appVersionKey')
    }

    private updateVersion(newVersion, versionKeyName) {
        def versionKey = config['MavenProperties'][versionKeyName]
        actionRunShell("ed -s ${config['MavenProperties'].path} <<< \$',s/^[[:blank:]]*$versionKey[[:blank:]]*=.*\$/$versionKey=$newVersion/g\nw'")
    }

    private def collectReports(filePattern, collectClosure) {
        context.findFiles(glob: filePattern)
                .collect(collectClosure)
                .unique()
                .join(',')
    }
}
