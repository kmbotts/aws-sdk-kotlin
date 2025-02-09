/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

// This build file has been adapted from the Go v2 SDK, here:
// https://github.com/aws/aws-sdk-go-v2/blob/master/codegen/sdk-codegen/build.gradle.kts

import software.amazon.smithy.gradle.tasks.SmithyBuild
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.ServiceShape
import java.util.*
import kotlin.streams.toList

description = "AWS SDK codegen tasks"

plugins {
    id("software.amazon.smithy")
}

buildscript {
    val smithyVersion: String by project
    dependencies {
        classpath("software.amazon.smithy:smithy-aws-traits:$smithyVersion")
        classpath("software.amazon.smithy:smithy-cli:$smithyVersion")
    }
}

dependencies {
    implementation(project(":codegen:smithy-aws-kotlin-codegen"))
}

// This project doesn't produce a JAR.
tasks["jar"].enabled = false

// Run the SmithyBuild task manually since this project needs the built JAR
tasks["smithyBuildJar"].enabled = false

// get a project property by name if it exists (including from local.properties)
fun getProperty(name: String): String? {
    if (project.hasProperty(name)) {
        return project.properties[name].toString()
    }

    val localProperties = Properties()
    val propertiesFile: File = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use { localProperties.load(it) }

        if (localProperties.containsKey(name)) {
            return localProperties[name].toString()
        }
    }
    return null
}

// Represents information needed to generate a smithy projection JSON stanza
data class AwsService(
    val name: String,
    val packageName: String,
    val packageVersion: String,
    val modelFile: File,
    val projectionName: String,
    val sdkId: String,
    val description: String? = null
)


val disabledServices = setOf(
    // transcribe streaming contains exclusively EventStream operations which are not supported
    "transcribestreaming",
    // timestream requires endpoint discovery
    // https://github.com/awslabs/smithy-kotlin/issues/146
    "timestreamwrite",
    "timestreamquery"
)

// Generates a smithy-build.json file by creating a new projection.
// The generated smithy-build.json file is not committed to git since
// it's rebuilt each time codegen is performed.
fun generateSmithyBuild(services: List<AwsService>): String {
    require(services.isNotEmpty()) {
        "No services discovered. Verify aws.services and aws.protocols properties in local.build. Aborting."
    }

    val projections = services.joinToString(",") { service ->
        // escape windows paths for valid json
        val absModelPath = service.modelFile.absolutePath.replace("\\", "\\\\")
        val importPaths = mutableListOf(absModelPath)
        if (file(service.modelExtrasDir).exists()) {
            importPaths.add(service.modelExtrasDir.replace("\\", "\\\\"))
        }
        val imports = importPaths.joinToString { "\"$it\"" }
        val transforms = transformsForService(service)

        """
            "${service.projectionName}": {
                "imports": [$imports],
                "plugins": {
                    "kotlin-codegen": {
                      "service": "${service.name}",
                      "package" : {
                          "name": "${service.packageName}",
                          "version": "${service.packageVersion}",
                          "description": "${service.description}"                      
                      },
                      "sdkId": "${service.sdkId}",
                      "build": {
                          "generateDefaultBuildFiles": false
                      }
                    }
                },
                "transforms": $transforms
            }
        """
    }

    return """
    {
        "version": "1.0",
        "projections": {
            $projections
        }
    }
    """.trimIndent()
}

/**
 * This function retrieves Smithy transforms associated with the target service to be merged into generated
 * `smithy-build.json` files.  The transform file MUST live in <service dir>/transforms.
 * The JSON fragment MUST be a JSON object of a transform as described on this page:
 * https://awslabs.github.io/smithy/1.0/guides/building-models/build-config.html#transforms.
 *
 * Transform filenames should follow the convention of:
 * <transform operation>-<target shape(s)>.json
 * Example: renameShapes-MarketplaceCommerceAnalyticsException.json
 */
fun transformsForService(service: AwsService): String {
    val transformsDir = File(service.transformsDir)
    return transformsDir.listFiles()?.map { transformFile ->
        transformFile.readText()
    }?.toString() ?: "[]"
}

val discoveredServices: List<AwsService> by lazy { discoverServices() }
// The root namespace prefix for SDKs
val sdkPackageNamePrefix = "aws.sdk.kotlin.services."

// Returns an AwsService model for every JSON file found in in directory defined by property `modelsDirProp`
fun discoverServices(): List<AwsService> {
    val modelsDir: String by project
    val serviceMembership = parseMembership(getProperty("aws.services"))
    val protocolMembership = parseMembership(getProperty("aws.protocols"))

    return fileTree(project.file(modelsDir))
        .filter { file ->
            val svcName = file.name.split(".").first()
            val include = serviceMembership.isMember(svcName)

            if (!include) {
                logger.info("skipping ${file.absolutePath}, $svcName not a member of $serviceMembership")
            }

            val isDisabled = svcName in disabledServices
            if (include && isDisabled) {
                logger.warn("skipping $svcName because it is explicitly disabled")
            }

            include && !isDisabled
        }
        .map { file ->
            val model = Model.assembler().addImport(file.absolutePath).assemble().result.get()
            val services: List<ServiceShape> = model.shapes(ServiceShape::class.java).sorted().toList()
            require(services.size == 1) { "Expected one service per aws model, but found ${services.size} in ${file.absolutePath}: ${services.map { it.id }}" }
            val service = services.first()
            file to service
        }
        .filter { (file, service) ->
            val protocol = service.protocol()
            val include = protocolMembership.isMember(protocol)

            if (!include) {
                logger.info("skipping ${file.absolutePath}, $protocol not a member of $protocolMembership")
            }
            include
        }
        .map { (file, service) ->
            val serviceApi = service.getTrait(software.amazon.smithy.aws.traits.ServiceTrait::class.java).orNull()
                ?: error { "Expected aws.api#service trait attached to model ${file.absolutePath}" }
            val (name, version, _) = file.name.split(".")

            val packageName = name.kotlinNamespace()
            val description = service.getTrait(software.amazon.smithy.model.traits.TitleTrait::class.java).map { it.value }.orNull()

            logger.info("discovered service: ${serviceApi.sdkId}")

            val sdkVersion: String by project
            AwsService(
                name = service.id.toString(),
                packageName = "$sdkPackageNamePrefix$packageName",
                packageVersion = sdkVersion,
                modelFile = file,
                projectionName = name + "." + version.toLowerCase(),
                sdkId = serviceApi.sdkId,
                description = description
            )
        }
}

// Returns the trait name of the protocol of the service
fun ServiceShape.protocol(): String =
    listOf(
        "aws.protocols#awsJson1_0",
        "aws.protocols#awsJson1_1",
        "aws.protocols#awsQuery",
        "aws.protocols#ec2Query",
        "aws.protocols#restJson1",
        "aws.protocols#restXml"
    ).first { protocol -> findTrait(protocol).isPresent }.split("#")[1]

// Class and functions for service and protocol membership for SDK generation
data class Membership(val inclusions: Set<String> = emptySet(), val exclusions: Set<String> = emptySet())
fun Membership.isMember(member: String): Boolean = when {
    exclusions.contains(member) -> false
    inclusions.contains(member) -> true
    inclusions.isEmpty() -> true
    else -> false
}
fun parseMembership(rawList: String?): Membership {
    if (rawList == null) return Membership()

    val inclusions = mutableSetOf<String>()
    val exclusions = mutableSetOf<String>()

    rawList.split(",").map { it.trim() }.forEach { item ->
        when {
            item.startsWith('-') -> exclusions.add(item.substring(1))
            item.startsWith('+') -> inclusions.add(item.substring(1))
            else -> error("Must specify inclusion (+) or exclusion (-) prefix character to $item.")
        }
    }

    val conflictingMembers = inclusions.intersect(exclusions)
    require(conflictingMembers.isEmpty()) { "$conflictingMembers specified both for inclusion and exclusion in $rawList" }

    return Membership(inclusions, exclusions)
}

fun <T> java.util.Optional<T>.orNull(): T? = this.orElse(null)

/**
 * Remove characters invalid for Kotlin package namespace identifier
 */
fun String.kotlinNamespace(): String = split(".")
    .joinToString(separator = ".") { segment -> segment.filter { it.isLetterOrDigit() } }

// Generate smithy-build.json as first step in build task
task("generateSmithyBuild") {
    group = "codegen"
    description = "generate smithy-build.json"
    doFirst {
        projectDir
            .resolve("smithy-build.json")
            .writeText(generateSmithyBuild(discoveredServices))
    }
}

tasks.create<SmithyBuild>("generateSdk") {
    group = "codegen"
    // ensure the generated clients use the same version of the runtime as the aws aws-runtime
    val smithyKotlinVersion: String by project
    doFirst {
        System.setProperty("smithy.kotlin.codegen.clientRuntimeVersion", smithyKotlinVersion)
    }

    addRuntimeClasspath = true
    dependsOn(tasks["generateSmithyBuild"])
    inputs.file(projectDir.resolve("smithy-build.json"))
    // ensure smithy-aws-kotlin-codegen is up to date
    inputs.files(configurations.compileClasspath)
}

// Remove generated model file for clean
tasks["clean"].doFirst {
    delete("smithy-build.json")
}

/**
 * The directory code is generated to
 */
val AwsService.projectionOutputDir: String
    get() = project.file("${project.buildDir}/smithyprojections/${project.name}/${projectionName}/kotlin-codegen").absolutePath

/**
 * The project directory under `aws-sdk-kotlin/services`
 */
val AwsService.destinationDir: String
    get(){
        val sanitizedName = projectionName.split(".")[0]
        return rootProject.file("services/${sanitizedName}").absolutePath
    }

/**
 * Service specific model extras
 */
val AwsService.modelExtrasDir: String
    get() = rootProject.file("${destinationDir}/model").absolutePath

/**
 * Defines where service-specific transforms are located
 */
val AwsService.transformsDir: String
    get() = rootProject.file("${destinationDir}/transforms").absolutePath

task("stageSdks") {
    group = "codegen"
    description = "relocate generated SDK(s) from build directory to services/ dir"
    dependsOn("generateSdk")
    doLast {
        discoveredServices.forEach {
            logger.info("copying ${it.projectionOutputDir} to ${it.destinationDir}")
            copy {
                from("${it.projectionOutputDir}/src")
                into("${it.destinationDir}/generated-src")
            }
            copy {
                from("${it.projectionOutputDir}/build.gradle.kts")
                into("${it.destinationDir}")
            }
        }
    }
}

tasks.create("bootstrap") {
    group = "codegen"
    description = "Generate AWS SDK's and register them with the build"

    dependsOn(tasks["generateSdk"])
    finalizedBy(tasks["stageSdks"])
}
