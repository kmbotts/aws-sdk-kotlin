/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

description = "HTTP core for AWS service clients"
extra["displayName"] = "Software :: AWS :: Kotlin SDK :: HTTP"
extra["moduleName"] = "aws.sdk.kotlin.runtime.http"

val smithyKotlinVersion: String by project

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":aws-runtime:aws-core"))
                api(project(":aws-runtime:regions"))
                api("aws.smithy.kotlin:http:$smithyKotlinVersion")
            }
        }

        commonTest {
            dependencies {
                implementation(project(":aws-runtime:testing"))
            }
        }
    }
}

